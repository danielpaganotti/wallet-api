package com.walletapi.transaction;

import com.walletapi.transaction.dto.CreateTransactionRequest;
import com.walletapi.transaction.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/wallets/{walletId}/transactions")
@Tag(name = "Transactions", description = "Register credit/debit operations and browse the wallet statement")
public class TransactionController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Register a credit or debit operation",
            description = "Requires an 'Idempotency-Key' header. Replaying the same key with the same " +
                    "payload returns the original result (HTTP 200). Reusing the key with a different " +
                    "payload is rejected with HTTP 409.")
    public ResponseEntity<TransactionResponse> register(
            @PathVariable UUID walletId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {

        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        TransactionService.Result result = transactionService.register(walletId, request, idempotencyKey);
        TransactionResponse body = TransactionResponse.from(result.transaction(), result.idempotentReplay());

        if (result.idempotentReplay()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/wallets/" + walletId + "/transactions/" + body.id())).body(body);
    }

    @GetMapping
    @Operation(summary = "List wallet transactions (statement)",
            description = "Paginated, newest first. Optionally filter by creation period with from/to (ISO-8601 instants).")
    public ResponseEntity<Page<TransactionResponse>> list(
            @PathVariable UUID walletId,
            @Parameter(description = "Start of period (inclusive), ISO-8601, e.g. 2025-01-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "End of period (inclusive), ISO-8601, e.g. 2025-01-31T23:59:59Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {

        Page<TransactionResponse> page = transactionService.listTransactions(walletId, from, to, pageable)
                .map(tx -> TransactionResponse.from(tx, false));
        return ResponseEntity.ok(page);
    }
}
