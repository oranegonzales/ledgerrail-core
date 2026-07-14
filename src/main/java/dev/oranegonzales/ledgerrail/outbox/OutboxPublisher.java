package dev.oranegonzales.ledgerrail.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledgerrail.kafka", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxPublishingRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaPublisherProperties properties;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;
    private final Timer sendTimer;

    public OutboxPublisher(
            OutboxPublishingRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaPublisherProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
        this.publishedCounter = meterRegistry.counter("ledgerrail.outbox.events", "outcome", "published");
        this.retryCounter = meterRegistry.counter("ledgerrail.outbox.events", "outcome", "retry");
        this.failedCounter = meterRegistry.counter("ledgerrail.outbox.events", "outcome", "failed");
        this.sendTimer = meterRegistry.timer("ledgerrail.outbox.publish.duration");
    }

    @Scheduled(
            fixedDelayString = "${ledgerrail.kafka.poll-interval-ms:2000}",
            initialDelayString = "${ledgerrail.kafka.initial-delay-ms:5000}")
    public void publishScheduled() {
        publishAvailable();
    }

    public int publishAvailable() {
        Instant claimTime = clock.instant();
        List<OutboxEvent> events = repository.claimBatch(
                properties.batchSize(), properties.leaseTimeout(), claimTime);
        int attempted = 0;
        for (OutboxEvent event : events) {
            attempted++;
            if (!publish(event)) {
                int released = repository.releaseClaim(
                        event.claimToken(), clock.instant(), "Publisher interrupted before delivery completed");
                retryCounter.increment(released);
                break;
            }
        }
        return attempted;
    }

    private boolean publish(OutboxEvent event) {
        Timer.Sample sample = Timer.start();
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    properties.topic(), event.aggregateId().toString(), event.payload());
            record.headers().add("event_id", event.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event_type", event.eventType().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (repository.markPublished(event.id(), event.claimToken(), clock.instant())) {
                publishedCounter.increment();
            } else {
                LOGGER.warn("Outbox event {} lost its publishing claim after Kafka acknowledged it", event.id());
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(event, exception);
            return false;
        } catch (Exception exception) {
            recordFailure(event, unwrap(exception));
            return true;
        } finally {
            sample.stop(sendTimer);
        }
    }

    private void recordFailure(OutboxEvent event, Throwable failure) {
        String error = failureMessage(failure);
        if (event.attempts() >= properties.maxAttempts()) {
            if (repository.markFailed(event.id(), event.claimToken(), error)) {
                failedCounter.increment();
            }
            LOGGER.error("Outbox event {} exhausted {} publish attempts: {}",
                    event.id(), event.attempts(), error);
            return;
        }
        Duration delay = retryDelay(event.attempts());
        if (repository.markForRetry(event.id(), event.claimToken(), clock.instant().plus(delay), error)) {
            retryCounter.increment();
        }
        LOGGER.warn("Outbox event {} publish attempt {} failed; retrying in {}: {}",
                event.id(), event.attempts(), delay, error);
    }

    private Duration retryDelay(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 30);
        Duration candidate;
        try {
            candidate = properties.initialBackoff().multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            return properties.maxBackoff();
        }
        return candidate.compareTo(properties.maxBackoff()) > 0
                ? properties.maxBackoff()
                : candidate;
    }

    private Throwable unwrap(Exception exception) {
        if (exception instanceof ExecutionException && exception.getCause() != null) {
            return exception.getCause();
        }
        return exception;
    }

    private String failureMessage(Throwable failure) {
        String detail = failure.getMessage();
        String message = detail == null || detail.isBlank()
                ? failure.getClass().getSimpleName()
                : "%s: %s".formatted(failure.getClass().getSimpleName(), detail);
        String singleLine = message.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() > 2000 ? singleLine.substring(0, 2000) : singleLine;
    }
}
