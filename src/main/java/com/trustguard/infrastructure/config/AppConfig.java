package com.trustguard.infrastructure.config;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rule 2.9: RabbitMQ unavailable is a partial outage (DEGRADED), never a
 * full outage (DOWN) — signal ingestion is impaired but checkRisk()
 * remains fully operational and is not on RabbitMQ's synchronous path
 * (Rule 2.8). Spring Boot Actuator's default AMQP health indicator only
 * distinguishes UP/DOWN, so this bean replaces it. The bean name
 * "rabbit" matches the contributor name Spring Boot's own
 * auto-configured indicator uses; application.yml disables that default
 * (management.health.rabbit.enabled=false) so exactly one RabbitMQ
 * indicator is active rather than two competing ones. Custom "DEGRADED"
 * status ordering and HTTP mapping are configured entirely via
 * application.yml properties, not here.
 *
 * Code v2 fixes (CF-002, CF-003):
 *   - catches Exception, not RuntimeException — AMQP drivers can throw
 *     checked exceptions that would otherwise escape this indicator
 *     entirely rather than producing a clean DEGRADED result.
 *   - the successful connection is now closed in a finally block,
 *     rather than being leaked when Health.up().build() short-circuited
 *     before cleanup ran.
 *   - the health detail no longer includes ex.getMessage() — raw AMQP
 *     failure messages routinely contain hostnames, ports, and
 *     credentials, which Rule 14.7 prohibits from ever reaching a
 *     response body.
 */
@Configuration
public class AppConfig {

    @Bean
    public HealthIndicator rabbit(ConnectionFactory connectionFactory) {
        return () -> {
            try {
                Connection connection = connectionFactory.createConnection();
                try {
                    return Health.up().build();
                } finally {
                    connection.close();
                }
            } catch (Exception ex) {
                return Health.status("DEGRADED")
                        .withDetail("reason", "RabbitMQ unreachable")
                        .build();
            }
        };
    }
}