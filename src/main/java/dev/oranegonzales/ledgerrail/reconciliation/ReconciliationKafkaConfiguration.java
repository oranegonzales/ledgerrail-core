package dev.oranegonzales.ledgerrail.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(prefix = "ledgerrail.kafka", name = "consumer-enabled", havingValue = "true")
class ReconciliationKafkaConfiguration {
}
