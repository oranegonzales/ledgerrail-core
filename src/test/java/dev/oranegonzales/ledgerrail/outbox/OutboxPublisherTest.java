package dev.oranegonzales.ledgerrail.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-14T20:00:00Z");

    @Mock
    private OutboxPublishingRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> recordCaptor;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new OutboxPublisher(
                repository,
                kafkaTemplate,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry);
    }

    @Test
    void publishesClaimedEventWithStableKeyAndHeaders() throws Exception {
        OutboxEvent event = event(1);
        when(repository.claimBatch(20, Duration.ofMinutes(5), NOW)).thenReturn(List.of(event));
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        when(repository.markPublished(event.id(), event.claimToken(), NOW)).thenReturn(true);

        assertThat(publisher.publishAvailable()).isEqualTo(1);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, String> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("transfer.completed.v1");
        assertThat(record.key()).isEqualTo(event.aggregateId().toString());
        assertThat(record.value()).isEqualTo(event.payload());
        assertThat(new String(record.headers().lastHeader("event_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(event.id().toString());
        assertThat(new String(record.headers().lastHeader("event_type").value(), StandardCharsets.UTF_8))
                .isEqualTo(event.eventType());
        verify(repository).markPublished(event.id(), event.claimToken(), NOW);
        assertThat(meterRegistry.get("ledgerrail.outbox.events")
                .tag("outcome", "published").counter().count()).isEqualTo(1.0);
    }

    @Test
    void returnsFailedSendToPendingWithExponentialBackoff() {
        OutboxEvent event = event(2);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker unavailable"));
        when(repository.claimBatch(20, Duration.ofMinutes(5), NOW)).thenReturn(List.of(event));
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenReturn(failed);
        when(repository.markForRetry(
                event.id(), event.claimToken(), NOW.plusSeconds(4), "TimeoutException: broker unavailable"))
                .thenReturn(true);

        assertThat(publisher.publishAvailable()).isEqualTo(1);

        verify(repository).markForRetry(
                event.id(), event.claimToken(), NOW.plusSeconds(4), "TimeoutException: broker unavailable");
        assertThat(meterRegistry.get("ledgerrail.outbox.events")
                .tag("outcome", "retry").counter().count()).isEqualTo(1.0);
    }

    @Test
    void marksEventFailedAfterFinalAttempt() {
        OutboxEvent event = event(3);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("invalid broker response"));
        when(repository.claimBatch(20, Duration.ofMinutes(5), NOW)).thenReturn(List.of(event));
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, String>>any())).thenReturn(failed);
        when(repository.markFailed(
                event.id(), event.claimToken(), "IllegalStateException: invalid broker response"))
                .thenReturn(true);

        assertThat(publisher.publishAvailable()).isEqualTo(1);

        verify(repository).markFailed(
                event.id(), event.claimToken(), "IllegalStateException: invalid broker response");
        assertThat(meterRegistry.get("ledgerrail.outbox.events")
                .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    private OutboxEvent event(int attempts) {
        return new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "transfer.completed.v1",
                "{\"status\":\"COMPLETED\"}",
                attempts,
                UUID.randomUUID());
    }

    private KafkaPublisherProperties properties() {
        return new KafkaPublisherProperties(
                true,
                "transfer.completed.v1",
                20,
                3,
                2000,
                5000,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                Duration.ofMinutes(5));
    }
}
