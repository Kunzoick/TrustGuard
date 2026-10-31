package com.trustguard.signals.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.trustguard.signals.mapper.EventEnvelopeMapper;
import com.trustguard.signals.parser.IngestionRequestParser;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;
import com.trustguard.signals.validation.IngestionRequestGuard;

import jakarta.validation.Validator;

/**
 * Wires the ingestion intake beans (Ruling 82): the envelope mapper, the strict request parser and
 * the request guard. The key-dependent generators come from {@link EventHmacConfig}, so the startup
 * gate for EVENT_HMAC_KEY still applies. The parser's JSON mapper is private to the parser and is
 * not a bean (Ruling 86).
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
}