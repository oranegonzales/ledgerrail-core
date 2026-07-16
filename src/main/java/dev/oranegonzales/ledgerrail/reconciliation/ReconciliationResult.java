package dev.oranegonzales.ledgerrail.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationResult(
        UUID eventId,
        UUID transferId,
        ReconciliationOutcome outcome,
        String detail,
        Instant processedAt) {
}
