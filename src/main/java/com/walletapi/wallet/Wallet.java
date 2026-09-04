package com.walletapi.wallet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_name", length = 150)
    private String ownerName;

    @Column(name = "owner_document", length = 50)
    private String ownerDocument;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);

    /**
     * Optimistic-locking safety net. The primary concurrency control for balance
     * mutations is the pessimistic row lock taken in
     * {@link WalletRepository#findByIdForUpdate(UUID)}; this version column guards
     * against any code path that updates the wallet without going through that lock.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
        // JPA
    }

    public Wallet(String ownerName, String ownerDocument) {
        this.ownerName = ownerName;
        this.ownerDocument = ownerDocument;
        this.balance = BigDecimal.ZERO.setScale(2);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getOwnerDocument() {
        return ownerDocument;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
