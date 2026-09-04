package com.walletapi.wallet.dto;

import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @Size(max = 150, message = "ownerName must be at most 150 characters")
        String ownerName,

        @Size(max = 50, message = "ownerDocument must be at most 50 characters")
        String ownerDocument
) {
}
