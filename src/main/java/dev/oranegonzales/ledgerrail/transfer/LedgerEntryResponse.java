package dev.oranegonzales.ledgerrail.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transferId,
        String accountCode,
        LedgerEntryType entryType,
        BigDecimal amount,
        String currency,
        Instant createdAt) {
}
