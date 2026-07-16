package dev.oranegonzales.ledgerrail.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxOperationsService {

    private final OutboxPublishingRepository repository;
    private final Clock clock;
    private final Counter replayCounter;

    OutboxOperationsService(
            OutboxPublishingRepository repository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.replayCounter = meterRegistry.counter("ledgerrail.outbox.replays", "outcome", "accepted");
    }

    List<OutboxStatus> failedEvents() {
        return repository.findFailed(100);
    }

    @Transactional
    OutboxStatus replay(UUID eventId) {
        Instant now = clock.instant();
        if (!repository.replayFailed(eventId, now)) {
            throw new OutboxReplayNotAllowedException(eventId);
        }
        replayCounter.increment();
        return repository.findStatus(eventId)
                .orElseThrow(() -> new IllegalStateException("Replayed outbox event could not be read"));
    }
}
