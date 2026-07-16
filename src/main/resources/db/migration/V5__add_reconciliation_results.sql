CREATE TABLE reconciliation_results (
    event_id UUID PRIMARY KEY,
    transfer_id UUID,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('PROCESSING', 'MATCHED', 'MISMATCH')),
    detail TEXT NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reconciliation_outcome_processed
    ON reconciliation_results (outcome, processed_at DESC);
