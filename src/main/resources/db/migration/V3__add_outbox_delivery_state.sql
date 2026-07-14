ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_status_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_status_check
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED'));

ALTER TABLE outbox_events
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN locked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claim_token UUID,
    ADD COLUMN last_error TEXT;

DROP INDEX idx_outbox_pending;

CREATE INDEX idx_outbox_publishable
    ON outbox_events (next_attempt_at, occurred_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_stale_claims
    ON outbox_events (locked_at)
    WHERE status = 'IN_FLIGHT';
