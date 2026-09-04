package com.walletapi.transaction;

import com.walletapi.transaction.dto.CreateTransactionRequest;
import com.walletapi.transaction.exception.IdempotencyConflictException;
import com.walletapi.transaction.exception.InsufficientBalanceException;
import com.walletapi.wallet.Wallet;
import com.walletapi.wallet.WalletRepository;
import com.walletapi.wallet.exception.WalletNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the business rules of {@link TransactionService}, with the
 * repositories mocked. Real concurrent-locking behaviour is exercised
 * separately in the Testcontainers-backed integration test.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    private Wallet walletWithBalance(BigDecimal balance) {
        Wallet wallet = new Wallet("Owner", "doc-1");
        ReflectionTestUtils.setField(wallet, "id", UUID.randomUUID());
        wallet.setBalance(balance);
        return wallet;
    }

    @Test
    void creditIncreasesBalanceAndPersistsTransaction() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.CREDIT, new BigDecimal("5.00"), "deposit");
        TransactionService.Result result = transactionService.register(wallet.getId(), request, "key-1");

        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.transaction().getBalanceAfter()).isEqualByComparingTo("15.00");
        assertThat(wallet.getBalance()).isEqualByComparingTo("15.00");
    }

    @Test
    void debitWithinBalanceSucceeds() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.DEBIT, new BigDecimal("4.00"), "withdrawal");
        TransactionService.Result result = transactionService.register(wallet.getId(), request, "key-2");

        assertThat(result.transaction().getBalanceAfter()).isEqualByComparingTo("6.00");
    }

    @Test
    void debitExceedingBalanceIsRejected() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.DEBIT, new BigDecimal("10.01"), "overdraft attempt");

        assertThatThrownBy(() -> transactionService.register(wallet.getId(), request, "key-3"))
                .isInstanceOf(InsufficientBalanceException.class);

        // balance must remain untouched and no transaction must be persisted
        assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void debitThatWouldExactlyZeroBalanceIsAllowed() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.DEBIT, new BigDecimal("10.00"), null);
        TransactionService.Result result = transactionService.register(wallet.getId(), request, "key-4");

        assertThat(result.transaction().getBalanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void replayingSameIdempotencyKeyWithSamePayloadReturnsOriginalResultWithoutTouchingBalance() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        WalletTransaction existingTx = new WalletTransaction(wallet, TransactionType.CREDIT, new BigDecimal("5.00"),
                new BigDecimal("15.00"), "key-5", "deposit");
        ReflectionTestUtils.setField(existingTx, "id", UUID.randomUUID());

        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(wallet.getId(), "key-5"))
                .thenReturn(Optional.of(existingTx));

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.CREDIT, new BigDecimal("5.00"), "deposit");
        TransactionService.Result result = transactionService.register(wallet.getId(), request, "key-5");

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.transaction()).isSameAs(existingTx);
        // balance on the in-memory wallet must be untouched: the wallet still holds its original 10.00
        assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void reusingIdempotencyKeyWithDifferentPayloadIsConflict() {
        Wallet wallet = walletWithBalance(new BigDecimal("10.00"));
        WalletTransaction existingTx = new WalletTransaction(wallet, TransactionType.CREDIT, new BigDecimal("5.00"),
                new BigDecimal("15.00"), "key-6", "deposit");
        ReflectionTestUtils.setField(existingTx, "id", UUID.randomUUID());

        when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByWallet_IdAndIdempotencyKey(wallet.getId(), "key-6"))
                .thenReturn(Optional.of(existingTx));

        // same key, but now a DEBIT instead of the original CREDIT
        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.DEBIT, new BigDecimal("5.00"), "deposit");

        assertThatThrownBy(() -> transactionService.register(wallet.getId(), request, "key-6"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void registeringOnMissingWalletThrowsNotFound() {
        UUID missingId = UUID.randomUUID();
        when(walletRepository.findByIdForUpdate(missingId)).thenReturn(Optional.empty());

        CreateTransactionRequest request = new CreateTransactionRequest(TransactionType.CREDIT, new BigDecimal("1.00"), null);

        assertThatThrownBy(() -> transactionService.register(missingId, request, "key-7"))
                .isInstanceOf(WalletNotFoundException.class);
    }
}
