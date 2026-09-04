package com.walletapi.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against a real PostgreSQL instance (Testcontainers), exercising
 * the full HTTP stack: wallet creation, statement pagination, insufficient-balance
 * rejection, idempotent replay/conflict, and — the core of the challenge —
 * concurrent credit/debit operations racing on the same wallet.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletTransactionIntegrationTest {

    private static final String API_KEY = "changeme-local-dev-key";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_api_test")
            .withUsername("wallet_api")
            .withPassword("wallet_api");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("api.security.api-key", () -> API_KEY);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", API_KEY);
        return headers;
    }

    private String createWallet() {
        HttpEntity<String> request = new HttpEntity<>("{\"ownerName\":\"Integration Test\"}", authHeaders());
        ResponseEntity<String> response = restTemplate.postForEntity(url("/wallets"), request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readField(response.getBody(), "id");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String readField(String json, String field) {
        try {
            return objectMapper.readTree(json).get(field).asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BigDecimal readDecimalField(String json, String field) {
        try {
            return objectMapper.readTree(json).get(field).decimalValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void endToEndWalletAndTransactionFlow() {
        String walletId = createWallet();

        // credit 100
        HttpHeaders creditHeaders = authHeaders();
        creditHeaders.set("Idempotency-Key", "flow-credit-1");
        HttpEntity<String> creditRequest = new HttpEntity<>(
                "{\"type\":\"CREDIT\",\"amount\":100.00,\"description\":\"initial deposit\"}", creditHeaders);
        ResponseEntity<String> creditResponse = restTemplate.postForEntity(
                url("/wallets/" + walletId + "/transactions"), creditRequest, String.class);
        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // wallet balance reflects the credit
        ResponseEntity<String> walletResponse = restTemplate.exchange(
                url("/wallets/" + walletId), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(readDecimalField(walletResponse.getBody(), "balance")).isEqualByComparingTo("100.00");

        // debit exceeding balance is rejected with 422
        HttpHeaders debitHeaders = authHeaders();
        debitHeaders.set("Idempotency-Key", "flow-debit-1");
        HttpEntity<String> overdraftRequest = new HttpEntity<>(
                "{\"type\":\"DEBIT\",\"amount\":500.00}", debitHeaders);
        ResponseEntity<String> overdraftResponse = restTemplate.postForEntity(
                url("/wallets/" + walletId + "/transactions"), overdraftRequest, String.class);
        assertThat(overdraftResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overdraftResponse.getBody()).contains("INSUFFICIENT_BALANCE");

        // idempotent replay: same key + same payload returns 200 with same transaction id
        HttpHeaders replayHeaders = authHeaders();
        replayHeaders.set("Idempotency-Key", "flow-credit-1");
        HttpEntity<String> replayRequest = new HttpEntity<>(
                "{\"type\":\"CREDIT\",\"amount\":100.00,\"description\":\"initial deposit\"}", replayHeaders);
        ResponseEntity<String> replayResponse = restTemplate.postForEntity(
                url("/wallets/" + walletId + "/transactions"), replayRequest, String.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readField(replayResponse.getBody(), "id")).isEqualTo(readField(creditResponse.getBody(), "id"));

        // reusing the same key with a different payload is a 409 conflict
        HttpHeaders conflictHeaders = authHeaders();
        conflictHeaders.set("Idempotency-Key", "flow-credit-1");
        HttpEntity<String> conflictRequest = new HttpEntity<>(
                "{\"type\":\"DEBIT\",\"amount\":1.00}", conflictHeaders);
        ResponseEntity<String> conflictResponse = restTemplate.postForEntity(
                url("/wallets/" + walletId + "/transactions"), conflictRequest, String.class);
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // missing API key is rejected with 401
        HttpEntity<String> noAuthRequest = new HttpEntity<>(new HttpHeaders());
        ResponseEntity<String> noAuthResponse = restTemplate.exchange(
                url("/wallets/" + walletId), HttpMethod.GET, noAuthRequest, String.class);
        assertThat(noAuthResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // statement lists the applied transaction (the rejected debit is not persisted)
        ResponseEntity<String> statementResponse = restTemplate.exchange(
                url("/wallets/" + walletId + "/transactions?page=0&size=20"), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(statementResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readField(statementResponse.getBody(), "totalElements")).isEqualTo("1");
    }

    @Test
    void concurrentDebitsNeverOverdrawTheWallet() throws InterruptedException {
        String walletId = createWallet();

        // fund the wallet with 100.00
        HttpHeaders fundHeaders = authHeaders();
        fundHeaders.set("Idempotency-Key", "concurrency-fund");
        restTemplate.postForEntity(url("/wallets/" + walletId + "/transactions"),
                new HttpEntity<>("{\"type\":\"CREDIT\",\"amount\":100.00}", fundHeaders), String.class);

        int attempts = 20; // 20 debits of 10.00 against a balance of 100.00: only 10 can succeed
        BigDecimal debitAmount = new BigDecimal("10.00");
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch readyLatch = new CountDownLatch(attempts);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<HttpStatusCode>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            int index = i;
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                HttpHeaders headers = authHeaders();
                headers.set("Idempotency-Key", "concurrency-debit-" + index);
                HttpEntity<String> request = new HttpEntity<>(
                        "{\"type\":\"DEBIT\",\"amount\":" + debitAmount + "}", headers);
                ResponseEntity<String> response = restTemplate.postForEntity(
                        url("/wallets/" + walletId + "/transactions"), request, String.class);
                return response.getStatusCode();
            });
        }

        // Submit all tasks first (non-blocking), then release the start gate only once
        // every worker thread has confirmed it's ready and parked on startLatch. Using
        // invokeAll() here would deadlock: it blocks the main thread until all tasks
        // finish, so it would never reach the point of counting down startLatch.
        List<Future<HttpStatusCode>> futures = new java.util.ArrayList<>();
        for (Callable<HttpStatusCode> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        long successCount = futures.stream().filter(f -> {
            try {
                return f.get() == HttpStatus.CREATED;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();
        long rejectedCount = futures.stream().filter(f -> {
            try {
                return f.get() == HttpStatus.UNPROCESSABLE_ENTITY;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();

        assertThat(successCount).isEqualTo(10);
        assertThat(rejectedCount).isEqualTo(attempts - 10);

        ResponseEntity<String> finalWallet = restTemplate.exchange(
                url("/wallets/" + walletId), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(readDecimalField(finalWallet.getBody(), "balance")).isEqualByComparingTo("0.00");
    }
}
