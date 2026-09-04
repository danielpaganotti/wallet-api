CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE wallets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name      VARCHAR(150),
    owner_document  VARCHAR(50),
    balance         NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_wallets_owner_document ON wallets (owner_document);
