package dev.rayongreen.ledgerrail.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record TransferRecord(
        UUID id,
        String idempotencyKey,
        UUID accountId,
        TransferType type,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        Instant createdAt) {

    TransferResponse toResponse() {
        return new TransferResponse(id, accountId, type, amount, currency, status, createdAt);
    }
}
