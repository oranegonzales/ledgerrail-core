ALTER TABLE outbox_events
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    ADD COLUMN last_replayed_at TIMESTAMP WITH TIME ZONE;
