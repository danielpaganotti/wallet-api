package com.walletapi.wallet.dto;

import com.walletapi.wallet.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        String ownerName,
        String ownerDocument,
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getOwnerName(),
                wallet.getOwnerDocument(),
                wallet.getBalance(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }
}
