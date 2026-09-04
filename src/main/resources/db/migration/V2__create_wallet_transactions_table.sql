CREATE TABLE wallet_transactions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id        UUID NOT NULL REFERENCES wallets (id),
    type             VARCHAR(10) NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    balance_after    NUMERIC(19, 2) NOT NULL,
    idempotency_key  VARCHAR(150) NOT NULL,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_wallet_transactions_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_wallet_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT uk_wallet_transactions_wallet_idempotency UNIQUE (wallet_id, idempotency_key)
);

CREATE INDEX idx_wallet_transactions_wallet_created_at ON wallet_transactions (wallet_id, created_at DESC);
