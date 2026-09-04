package com.walletapi.transaction;

import com.walletapi.transaction.dto.CreateTransactionRequest;
import com.walletapi.transaction.exception.IdempotencyConflictException;
import com.walletapi.transaction.exception.InsufficientBalanceException;
import com.walletapi.wallet.Wallet;
import com.walletapi.wallet.WalletRepository;
import com.walletapi.wallet.exception.WalletNotFoundException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Registers a credit or debit against a wallet.
     * <p>
     * Concurrency strategy (see README for full rationale):
     * <ol>
     *   <li>Acquire a {@code SELECT ... FOR UPDATE} pessimistic write lock on the
     *       wallet row via {@link WalletRepository#findByIdForUpdate}. This serializes
     *       every operation targeting the same wallet, so no two transactions can
     *       read-check-write the balance concurrently.</li>
     *   <li>While holding that lock, check for an existing transaction with the same
     *       (wallet, idempotency key). If found, the request is a retry: no balance
     *       change is applied and the original result is returned.</li>
     *   <li>Otherwise validate the business rule (debit must not overdraw), insert the
     *       new transaction row and update the wallet balance, all in one DB
     *       transaction that only commits when the lock is released.</li>
     * </ol>
     * A unique DB constraint on (wallet_id, idempotency_key) is kept as a defense in
     * depth: if it is ever violated despite the lock, a {@link DataIntegrityViolationException}
     * is translated by {@link com.walletapi.common.GlobalExceptionHandler} into a 409.
     */
    @Transactional
    public Result register(UUID walletId, CreateTransactionRequest request, String idempotencyKey) {
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        Optional<WalletTransaction> existing =
                transactionRepository.findByWallet_IdAndIdempotencyKey(walletId, idempotencyKey);
        if (existing.isPresent()) {
            WalletTransaction tx = existing.get();
            BigDecimal requestedAmount = request.amount().setScale(2, RoundingMode.HALF_UP);
            if (tx.getType() != request.type() || tx.getAmount().compareTo(requestedAmount) != 0) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new Result(tx, true);
        }

        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal newBalance = switch (request.type()) {
            case CREDIT -> wallet.getBalance().add(amount);
            case DEBIT -> {
                BigDecimal candidate = wallet.getBalance().subtract(amount);
                if (candidate.signum() < 0) {
                    throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
                }
                yield candidate;
            }
        };

        WalletTransaction tx = new WalletTransaction(wallet, request.type(), amount, newBalance,
                idempotencyKey, request.description());
        wallet.setBalance(newBalance);

        try {
            transactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            // Extremely unlikely given the pessimistic lock above, but kept as a safety
            // net in case the lock is ever bypassed (e.g. a future code path).
            throw e;
        }
        walletRepository.save(wallet);

        return new Result(tx, false);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransaction> listTransactions(UUID walletId, Instant from, Instant to, Pageable pageable) {
        if (!walletRepository.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        Specification<WalletTransaction> spec = periodSpecification(walletId, from, to);
        return transactionRepository.findAll(spec, pageable);
    }

    private static Specification<WalletTransaction> periodSpecification(UUID walletId, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("wallet").get("id"), walletId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public record Result(WalletTransaction transaction, boolean idempotentReplay) {
    }
}
