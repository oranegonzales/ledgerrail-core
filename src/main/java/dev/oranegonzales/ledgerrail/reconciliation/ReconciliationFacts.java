package dev.oranegonzales.ledgerrail.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

record ReconciliationFacts(
        UUID accountId,
        String type,
        BigDecimal amount,
        String currency,
        String status,
        int entryCount,
        int debitCount,
        int creditCount,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        boolean ledgerCurrenciesMatch,
        boolean outboxPresent) {
}
