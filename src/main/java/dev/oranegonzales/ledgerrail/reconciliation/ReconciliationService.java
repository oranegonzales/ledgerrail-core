package dev.oranegonzales.ledgerrail.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private final ReconciliationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter matchedCounter;
    private final Counter mismatchCounter;
    private final Counter duplicateCounter;

    ReconciliationService(
            ReconciliationRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.matchedCounter = meterRegistry.counter("ledgerrail.reconciliation.events", "outcome", "matched");
        this.mismatchCounter = meterRegistry.counter("ledgerrail.reconciliation.events", "outcome", "mismatch");
        this.duplicateCounter = meterRegistry.counter("ledgerrail.reconciliation.events", "outcome", "duplicate");
    }

    @Transactional
    public void reconcile(UUID headerEventId, String payload) {
        TransferCompletedEvent event = parse(payload);
        Instant now = clock.instant();
        if (!repository.claim(headerEventId, event.transferId(), now)) {
            duplicateCounter.increment();
            return;
        }

        List<String> mismatches = new ArrayList<>();
        if (!headerEventId.equals(event.eventId())) {
            mismatches.add("event header does not match payload eventId");
        }
        repository.facts(event.transferId(), headerEventId).ifPresentOrElse(
                facts -> compare(event, facts, mismatches),
                () -> mismatches.add("transfer does not exist in PostgreSQL"));

        if (mismatches.isEmpty()) {
            repository.complete(headerEventId, ReconciliationOutcome.MATCHED,
                    "Transfer, ledger, payload, and outbox agree", now);
            matchedCounter.increment();
            return;
        }

        repository.complete(headerEventId, ReconciliationOutcome.MISMATCH,
                String.join("; ", mismatches), now);
        mismatchCounter.increment();
    }

    private TransferCompletedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, TransferCompletedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Kafka transfer event is not valid JSON", exception);
        }
    }

    private void compare(
            TransferCompletedEvent event,
            ReconciliationFacts facts,
            List<String> mismatches) {
        compareValue("accountId", facts.accountId(), event.accountId(), mismatches);
        compareValue("type", facts.type(), event.type(), mismatches);
        compareMoney("amount", facts.amount(), event.amount(), mismatches);
        compareValue("currency", facts.currency(), event.currency(), mismatches);
        compareValue("status", facts.status(), event.status(), mismatches);
        if (facts.entryCount() != 2 || facts.debitCount() != 1 || facts.creditCount() != 1) {
            mismatches.add("ledger must contain exactly one debit and one credit");
        }
        compareMoney("debit total", facts.amount(), facts.debitTotal(), mismatches);
        compareMoney("credit total", facts.amount(), facts.creditTotal(), mismatches);
        if (!facts.ledgerCurrenciesMatch()) {
            mismatches.add("ledger currency differs from transfer currency");
        }
        if (!facts.outboxPresent()) {
            mismatches.add("matching transactional outbox event is missing");
        }
    }

    private void compareValue(String name, Object authoritative, Object eventValue, List<String> mismatches) {
        if (authoritative == null ? eventValue != null : !authoritative.equals(eventValue)) {
            mismatches.add(name + " differs from PostgreSQL");
        }
    }

    private void compareMoney(String name, BigDecimal authoritative, BigDecimal candidate, List<String> mismatches) {
        if (authoritative == null || candidate == null || authoritative.compareTo(candidate) != 0) {
            mismatches.add(name + " differs from PostgreSQL");
        }
    }
}
