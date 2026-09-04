package com.walletapi.wallet;

import com.walletapi.wallet.dto.CreateWalletRequest;
import com.walletapi.wallet.exception.WalletNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet create(CreateWalletRequest request) {
        Wallet wallet = new Wallet(request.ownerName(), request.ownerDocument());
        return walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public Wallet getById(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }
}
