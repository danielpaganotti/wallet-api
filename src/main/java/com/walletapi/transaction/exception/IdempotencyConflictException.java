package com.walletapi.transaction.exception;

/**
 * Thrown when an idempotency key is reused for an operation whose
 * type/amount/wallet differs from the original request that first used it.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '%s' was already used with a different operation payload".formatted(idempotencyKey));
    }
}
