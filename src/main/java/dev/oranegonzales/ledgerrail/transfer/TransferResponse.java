package dev.oranegonzales.ledgerrail.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID accountId,
        TransferType type,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        Instant createdAt) {
}
