package com.walletapi.wallet;

import com.walletapi.wallet.dto.CreateWalletRequest;
import com.walletapi.wallet.dto.WalletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
@Tag(name = "Wallets", description = "Wallet creation and balance lookup")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @Operation(summary = "Create a new wallet", description = "Creates a wallet with an initial balance of 0.00")
    public ResponseEntity<WalletResponse> create(@Valid @RequestBody(required = false) CreateWalletRequest request) {
        CreateWalletRequest effectiveRequest = request == null ? new CreateWalletRequest(null, null) : request;
        Wallet wallet = walletService.create(effectiveRequest);
        WalletResponse body = WalletResponse.from(wallet);
        return ResponseEntity.created(URI.create("/wallets/" + wallet.getId())).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get wallet by id", description = "Returns the current balance and owner data of a wallet")
    public ResponseEntity<WalletResponse> getById(@PathVariable UUID id) {
        Wallet wallet = walletService.getById(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
