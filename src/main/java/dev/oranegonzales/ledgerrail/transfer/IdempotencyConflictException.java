package dev.oranegonzales.ledgerrail.transfer;

class IdempotencyConflictException extends RuntimeException {

    IdempotencyConflictException() {
        super("The idempotency key was already used with a different request");
    }
}
