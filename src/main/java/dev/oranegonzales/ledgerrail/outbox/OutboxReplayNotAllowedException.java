package dev.oranegonzales.ledgerrail.outbox;

import java.util.UUID;

public class OutboxReplayNotAllowedException extends RuntimeException {

    public OutboxReplayNotAllowedException(UUID eventId) {
        super("Outbox event %s does not exist or is not in FAILED state".formatted(eventId));
    }
}
