package dev.oranegonzales.ledgerrail.transfer;

class InvalidIdempotencyKeyException extends RuntimeException {

    InvalidIdempotencyKeyException() {
        super("Idempotency-Key must contain 8 to 100 letters, numbers, periods, underscores, colons, or hyphens");
    }
}
