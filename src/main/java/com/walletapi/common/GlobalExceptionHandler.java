package com.walletapi.common;

import com.walletapi.transaction.exception.IdempotencyConflictException;
import com.walletapi.transaction.exception.InsufficientBalanceException;
import com.walletapi.wallet.exception.WalletNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Central place translating domain/framework exceptions into the standardized
 * {@link ApiError} payload with a semantically correct HTTP status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ApiError> handleWalletNotFound(WalletNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.WALLET_NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiError> handleInsufficientBalance(InsufficientBalanceException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.INSUFFICIENT_BALANCE, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), message(fe)))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request validation failed", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Malformed request body", request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_HEADER, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        // Safety net: unique constraint violations that slip past the application-level
        // idempotency check (e.g. two requests racing before the pessimistic lock is
        // acquired) surface here and are reported as a conflict.
        return build(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Duplicate operation detected", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected internal error", request, List.of());
    }

    private static String message(FieldError fe) {
        return fe.getDefaultMessage() == null ? "invalid value" : fe.getDefaultMessage();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                            HttpServletRequest request, List<ApiError.FieldViolation> details) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), code, message, request.getRequestURI(), details);
        return ResponseEntity.status(status).body(body);
    }
}
