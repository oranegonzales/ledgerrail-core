package dev.oranegonzales.ledgerrail.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/outbox")
class OutboxOperationsController {

    private final OutboxOperationsService service;

    OutboxOperationsController(OutboxOperationsService service) {
        this.service = service;
    }

    @GetMapping("/failed")
    List<OutboxStatus> failedEvents() {
        return service.failedEvents();
    }

    @PostMapping("/{eventId}/replay")
    ResponseEntity<OutboxStatus> replay(@PathVariable UUID eventId) {
        return ResponseEntity.accepted().body(service.replay(eventId));
    }
}
