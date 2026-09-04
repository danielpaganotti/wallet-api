package com.walletapi.common;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error payload returned by every failed API call.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> details
) {

    public static ApiError of(int status, String error, String code, String message, String path) {
        return new ApiError(Instant.now(), status, error, code, message, path, List.of());
    }

    public static ApiError of(int status, String error, String code, String message, String path,
                               List<FieldViolation> details) {
        return new ApiError(Instant.now(), status, error, code, message, path, details);
    }

    public record FieldViolation(String field, String message) {
    }
}
