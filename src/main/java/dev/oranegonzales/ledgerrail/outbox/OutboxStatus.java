package dev.oranegonzales.ledgerrail.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxStatus(
        UUID eventId,
        UUID transferId,
        String eventType,
        String status,
        int attempts,
        int replayCount,
        Instant nextAttemptAt,
        Instant lastReplayedAt,
        String lastError) {
}
