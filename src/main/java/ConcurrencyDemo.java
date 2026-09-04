import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Self-contained, dependency-free reproduction of the concurrency scenarios exercised by
 * {@code WalletTransactionIntegrationTest}, meant to be run against an ALREADY RUNNING
 * instance of the app (started via IntelliJ, `mvn spring-boot:run`, or `docker compose up`).
 *
 * It intentionally avoids JUnit, Spring, Jackson and Testcontainers so it can be compiled
 * and executed with nothing but a JDK 17+ (single-file source launch, JEP 330), sidestepping
 * any local Docker/Testcontainers environment issues (colima socket, Ryuk, API version, etc.)
 * while still exercising the exact same HTTP endpoints under real concurrent load.
 *
 * Usage:
 *   java ConcurrencyDemo.java [baseUrl] [apiKey]
 *
 * Defaults:
 *   baseUrl = http://localhost:8080   (IntelliJ / mvn spring-boot:run default port)
 *   apiKey  = changeme-local-dev-key  (application.yml default)
 *
 * If you're running the full stack via docker-compose instead, pass the mapped port:
 *   java ConcurrencyDemo.java http://localhost:8081 changeme-local-dev-key
 *
 * Exit code is 0 if every scenario passes, non-zero otherwise (so it can be wired into CI).
 */
public class ConcurrencyDemo {

    private static String baseUrl = "http://localhost:8081";
    private static String apiKey = "changeme-local-dev-key";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        if (args.length > 0) baseUrl = args[0];
        if (args.length > 1) apiKey = args[1];

        System.out.println("== Wallet API concurrency demo ==");
        System.out.println("Target: " + baseUrl + " (API key: " + apiKey + ")");
        System.out.println();

        boolean healthOk = checkReachable();
        if (!healthOk) {
            System.err.println("Could not reach " + baseUrl + "/wallets. Is the app running?");
            System.err.println("Start it via IntelliJ (port 8080) or `docker compose up -d` (port 8081),");
            System.err.println("then re-run: java ConcurrencyDemo.java <baseUrl> <apiKey>");
            System.exit(2);
        }

        List<Boolean> results = new ArrayList<>();
        results.add(runConcurrentDebitsScenario());
        results.add(runIdempotencyRaceScenario());

        System.out.println();
        boolean allPassed = results.stream().allMatch(Boolean::booleanValue);
        System.out.println(allPassed ? "ALL SCENARIOS PASSED" : "SOME SCENARIOS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    // ---------------------------------------------------------------------
    // Scenario 1: 20 concurrent debits of 10.00 against a wallet funded with
    // 100.00. Exactly 10 must succeed (201), 10 must be rejected (422), and
    // the final balance must land exactly on 0.00 — never negative, never
    // double-applied.
    // ---------------------------------------------------------------------
    private static boolean runConcurrentDebitsScenario() throws InterruptedException {
        System.out.println("--- Scenario 1: concurrent debits never overdraw the wallet ---");

        String walletId = createWallet();
        System.out.println("Created wallet " + walletId);

        post("/wallets/" + walletId + "/transactions",
                "{\"type\":\"CREDIT\",\"amount\":100.00}", "demo-fund-" + walletId);

        int attempts = 20;
        BigDecimal debitAmount = new BigDecimal("10.00");
        int expectedSuccess = 10;

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch readyLatch = new CountDownLatch(attempts);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            int index = i;
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                HttpResponse<String> response = post(
                        "/wallets/" + walletId + "/transactions",
                        "{\"type\":\"DEBIT\",\"amount\":" + debitAmount + "}",
                        "demo-debit-" + walletId + "-" + index);
                return response.statusCode();
            });
        }

        // Submit every task first (non-blocking), then release the gate only once all
        // worker threads are parked on startLatch. invokeAll() would deadlock here since
        // it blocks the submitting thread until every task completes.
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        long successCount = countStatus(futures, 201);
        long rejectedCount = countStatus(futures, 422);

        HttpResponse<String> finalWallet = get("/wallets/" + walletId);
        String balance = extractField(finalWallet.body(), "balance");

        System.out.println("Successful debits (201): " + successCount + " (expected " + expectedSuccess + ")");
        System.out.println("Rejected debits (422):   " + rejectedCount + " (expected " + (attempts - expectedSuccess) + ")");
        System.out.println("Final balance:           " + balance + " (expected 0.00)");

        boolean pass = successCount == expectedSuccess
                && rejectedCount == (attempts - expectedSuccess)
                && new BigDecimal(balance).compareTo(BigDecimal.ZERO) == 0;
        System.out.println(pass ? "PASS" : "FAIL");
        System.out.println();
        return pass;
    }

    // ---------------------------------------------------------------------
    // Scenario 2: 15 concurrent requests reusing the exact same Idempotency-Key
    // and payload. Exactly one must be applied (201), the rest must replay the
    // same result (200) with the same transaction id, and the balance must
    // change only once.
    // ---------------------------------------------------------------------
    private static boolean runIdempotencyRaceScenario() throws InterruptedException {
        System.out.println("--- Scenario 2: same idempotency key applied only once under concurrency ---");

        String walletId = createWallet();
        post("/wallets/" + walletId + "/transactions",
                "{\"type\":\"CREDIT\",\"amount\":50.00}", "demo-fund2-" + walletId);

        int attempts = 15;
        String idempotencyKey = "demo-race-key-" + walletId;
        String payload = "{\"type\":\"DEBIT\",\"amount\":20.00}";

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch readyLatch = new CountDownLatch(attempts);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<String[]>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                HttpResponse<String> response = post(
                        "/wallets/" + walletId + "/transactions", payload, idempotencyKey);
                String id = extractField(response.body(), "id");
                return new String[] { String.valueOf(response.statusCode()), id };
            });
        }

        List<Future<String[]>> futures = new ArrayList<>();
        for (Callable<String[]> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        List<String[]> resultsList = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

        long created = resultsList.stream().filter(r -> r[0].equals("201")).count();
        long replayed = resultsList.stream().filter(r -> r[0].equals("200")).count();
        Set<String> distinctIds = resultsList.stream().map(r -> r[1]).collect(Collectors.toSet());

        HttpResponse<String> finalWallet = get("/wallets/" + walletId);
        String balance = extractField(finalWallet.body(), "balance");

        System.out.println("Created (201):   " + created + " (expected 1)");
        System.out.println("Replayed (200):  " + replayed + " (expected " + (attempts - 1) + ")");
        System.out.println("Distinct txn ids across all responses: " + distinctIds.size() + " (expected 1)");
        System.out.println("Final balance:   " + balance + " (expected 30.00, i.e. debited exactly once)");

        boolean pass = created == 1
                && replayed == (attempts - 1)
                && distinctIds.size() == 1
                && new BigDecimal(balance).compareTo(new BigDecimal("30.00")) == 0;
        System.out.println(pass ? "PASS" : "FAIL");
        System.out.println();
        return pass;
    }

    // ---------------------------------------------------------------------
    // Minimal HTTP + JSON helpers (no external dependencies)
    // ---------------------------------------------------------------------

    private static boolean checkReachable() {
        try {
            get("/wallets/00000000-0000-0000-0000-000000000000");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String createWallet() {
        HttpResponse<String> response = post("/wallets", "{\"ownerName\":\"Concurrency Demo\"}", null);
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to create wallet, status=" + response.statusCode()
                    + " body=" + response.body());
        }
        return extractField(response.body(), "id");
    }

    private static HttpResponse<String> post(String path, String jsonBody, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("POST " + path + " failed", e);
        }
    }

    private static HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("GET " + path + " failed", e);
        }
    }

    /** Extracts a top-level flat JSON field value as a raw string (strips quotes if present). */
    private static String extractField(String json, String field) {
        if (json == null) return null;
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\"([^\"]*)\"|[-0-9.eE]+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new RuntimeException("Field '" + field + "' not found in JSON: " + json);
        }
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
    }

    private static long countStatus(List<Future<Integer>> futures, int status) {
        AtomicInteger count = new AtomicInteger();
        futures.forEach(f -> {
            try {
                if (f.get() == status) count.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return count.get();
    }
}
