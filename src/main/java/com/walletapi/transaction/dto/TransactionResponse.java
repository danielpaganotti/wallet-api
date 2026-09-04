package com.walletapi.transaction.dto;

import com.walletapi.transaction.TransactionType;
import com.walletapi.transaction.WalletTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID walletId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String idempotencyKey,
        String description,
        Instant createdAt,
        boolean idempotentReplay
) {
    public static TransactionResponse from(WalletTransaction tx, boolean idempotentReplay) {
        return new TransactionResponse(
                tx.getId(),
                tx.getWallet().getId(),
                tx.getType(),
                tx.getAmount(),
                tx.getBalanceAfter(),
                tx.getIdempotencyKey(),
                tx.getDescription(),
                tx.getCreatedAt(),
                idempotentReplay
        );
    }
}
