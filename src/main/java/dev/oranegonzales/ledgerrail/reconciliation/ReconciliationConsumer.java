package dev.oranegonzales.ledgerrail.reconciliation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledgerrail.kafka", name = "consumer-enabled", havingValue = "true")
class ReconciliationConsumer {

    private final ReconciliationService service;

    ReconciliationConsumer(ReconciliationService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "${ledgerrail.kafka.topic}",
            groupId = "${ledgerrail.kafka.consumer-group:ledgerrail-reconciliation-v1}")
    void consume(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("event_id");
        if (header == null) {
            throw new IllegalArgumentException("Kafka transfer event is missing event_id header");
        }
        UUID eventId = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        service.reconcile(eventId, record.value());
    }
}
