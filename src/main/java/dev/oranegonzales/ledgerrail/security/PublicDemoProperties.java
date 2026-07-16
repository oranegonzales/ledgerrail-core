package dev.oranegonzales.ledgerrail.security;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ledgerrail.security.public-demo")
public record PublicDemoProperties(
        boolean enabled,
        @Min(1) int requestsPerMinute,
        @Min(1) int writesPerDay,
        boolean trustForwardedFor) {
}
