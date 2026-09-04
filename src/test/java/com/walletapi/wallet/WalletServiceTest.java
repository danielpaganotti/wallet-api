package com.walletapi.wallet;

import com.walletapi.wallet.dto.CreateWalletRequest;
import com.walletapi.wallet.exception.WalletNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private WalletService walletService;

    @Test
    void createsWalletWithZeroInitialBalance() {
        when(walletRepository.save(any(Wallet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Wallet created = walletService.create(new CreateWalletRequest("Jane Doe", "12345678900"));

        assertThat(created.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getOwnerName()).isEqualTo("Jane Doe");
        assertThat(created.getOwnerDocument()).isEqualTo("12345678900");
        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    void returnsWalletWhenFound() {
        Wallet wallet = new Wallet("John", "999");
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet));

        Wallet result = walletService.getById(id);

        assertThat(result).isSameAs(wallet);
    }

    @Test
    void throwsWalletNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getById(id))
                .isInstanceOf(WalletNotFoundException.class);
    }
}
