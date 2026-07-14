package dev.oranegonzales.ledgerrail.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ledgerrail.kafka")
public record KafkaPublisherProperties(
        boolean enabled,
        @NotBlank String topic,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts,
        @Min(1) long pollIntervalMs,
        @Min(0) long initialDelayMs,
        @NotNull Duration sendTimeout,
        @NotNull Duration leaseTimeout,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {

    public KafkaPublisherProperties {
        requirePositive("send-timeout", sendTimeout);
        requirePositive("lease-timeout", leaseTimeout);
        requirePositive("initial-backoff", initialBackoff);
        requirePositive("max-backoff", maxBackoff);
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("max-backoff must be greater than or equal to initial-backoff");
        }
        if (batchSize > 0 && leaseTimeout.compareTo(sendTimeout.multipliedBy(batchSize)) <= 0) {
            throw new IllegalArgumentException("lease-timeout must exceed batch-size multiplied by send-timeout");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
