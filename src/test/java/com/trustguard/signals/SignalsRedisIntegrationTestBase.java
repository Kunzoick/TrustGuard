package com.trustguard.signals;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Like {@link SignalsIntegrationTestBase}, but Redis is a real container and the server listens on a random port.
 * RabbitMQ stays unreachable (Ruling 100). The Redis location is registered as spring.data.redis.url, which Spring
 * Boot gives precedence over the host and port that the parent registers for its closed port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class SignalsRedisIntegrationTestBase extends SignalsIntegrationTestBase {

    private static final int REDIS_PORT = 6379;

    /** One Redis container per JVM, like the PostgreSQL container of the parent. */
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }
}