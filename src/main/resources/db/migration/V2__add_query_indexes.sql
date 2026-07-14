CREATE INDEX idx_transfers_account_created ON transfers(account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_transfer ON ledger_entries(transfer_id, created_at);
CREATE INDEX idx_outbox_pending ON outbox_events(occurred_at) WHERE status = 'PENDING';
