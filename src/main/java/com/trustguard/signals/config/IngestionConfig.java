package com.trustguard.signals.config;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.trustguard.signals.mapper.EventEnvelopeMapper;
import com.trustguard.signals.parser.IngestionRequestParser;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.EventIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;
import com.trustguard.signals.validation.IngestionRequestGuard;

import jakarta.validation.Validator;

/**
 * Wires the ingestion beans (Ruling 82): the envelope mapper, the strict request parser, the request guard and the
 * event id generator. The key-dependent generators come from {@link EventHmacConfig}, so the startup gate for
 * EVENT_HMAC_KEY still applies. The parser's JSON mapper is private to the parser and is not a bean (Ruling 86).
 * The event id generator gets its SecureRandom here and no SecureRandom bean exists.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionConfig {

    /**
     * Builds the envelope mapper.
     *
     * @param anonymousActorIdGenerator computes the actor id of ANONYMOUS events
     * @param idempotencyKeyGenerator   computes the fallback idempotency key
     * @return the mapper
     */
    @Bean
    public EventEnvelopeMapper eventEnvelopeMapper(AnonymousActorIdGenerator anonymousActorIdGenerator,
                                                   IdempotencyKeyGenerator idempotencyKeyGenerator) {
        return new EventEnvelopeMapper(anonymousActorIdGenerator, idempotencyKeyGenerator);
    }

    /**
     * Builds the request parser.
     *
     * @param validator the Bean Validation validator
     * @return the parser
     */
    @Bean
    public IngestionRequestParser ingestionRequestParser(Validator validator) {
        return new IngestionRequestParser(validator);
    }

    /**
     * Builds the request guard.
     *
     * @return the guard
     */
    @Bean
    public IngestionRequestGuard ingestionRequestGuard() {
        return new IngestionRequestGuard();
    }

    /**
     * Builds the event id generator (UUID v7).
     *
     * @param clock the platform clock
     * @return the generator, with its own SecureRandom
     */
    @Bean
    public EventIdGenerator eventIdGenerator(Clock clock) {
        return new EventIdGenerator(clock, new SecureRandom());
    }
}