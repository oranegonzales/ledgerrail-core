package dev.oranegonzales.ledgerrail.transfer;

import java.util.UUID;

class TransferNotFoundException extends RuntimeException {

    TransferNotFoundException(UUID id) {
        super("Transfer %s was not found".formatted(id));
    }
}
