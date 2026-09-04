package com.walletapi.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Acquires a {@code SELECT ... FOR UPDATE} row lock on the wallet.
     * This is the core concurrency control of the service: every balance
     * mutation goes through this method first, which serializes all
     * concurrent credit/debit operations against the same wallet at the
     * database level. See README "Concurrency strategy" for the full
     * rationale.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);
}
