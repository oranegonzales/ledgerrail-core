CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    account_id UUID NOT NULL,
    transfer_type VARCHAR(20) NOT NULL CHECK (transfer_type IN ('PAY_IN', 'PAY_OUT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = UPPER(currency)),
    status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_transfer_account_idempotency UNIQUE (account_id, idempotency_key)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_code VARCHAR(100) NOT NULL,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = UPPER(currency)),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);
