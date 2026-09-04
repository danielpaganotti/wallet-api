package com.walletapi.transaction.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID walletId, BigDecimal currentBalance, BigDecimal requestedDebit) {
        super("Wallet %s has insufficient balance: current=%s, requested debit=%s"
                .formatted(walletId, currentBalance, requestedDebit));
    }
}
