package dev.oranegonzales.ledgerrail.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID accountId,
        String type,
        BigDecimal amount,
        String currency,
        String status,
        Instant occurredAt) {
}
