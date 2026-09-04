package com.walletapi.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<WalletTransaction, UUID>,
        JpaSpecificationExecutor<WalletTransaction> {

    Optional<WalletTransaction> findByWallet_IdAndIdempotencyKey(UUID walletId, String idempotencyKey);
}
