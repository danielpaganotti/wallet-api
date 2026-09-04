package com.walletapi.transaction.dto;

import com.walletapi.transaction.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateTransactionRequest(
        @NotNull(message = "type is required")
        TransactionType type,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than zero")
        BigDecimal amount,

        @Size(max = 255, message = "description must be at most 255 characters")
        String description
) {
}
