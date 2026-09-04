package com.walletapi.common;

/**
 * Stable machine-readable error codes exposed in {@link ApiError#code()}.
 * Kept separate from HTTP status so clients can branch on business meaning
 * instead of parsing messages.
 */
public final class ErrorCode {

    public static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    public static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String MISSING_HEADER = "MISSING_HEADER";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCode() {
    }
}
