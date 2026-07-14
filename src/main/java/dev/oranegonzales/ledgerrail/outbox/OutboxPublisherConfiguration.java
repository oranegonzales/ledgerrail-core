package dev.oranegonzales.ledgerrail.outbox;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OutboxPublisherConfiguration {

    @Bean
    Clock ledgerRailClock() {
        return Clock.systemUTC();
    }
}
