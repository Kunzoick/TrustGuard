package com.trustguard.signals.validation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.trustguard.signals.constants.SignalConstants;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The actorId of an event must match its actorType (Rule 6.3): USER and SERVICE need a
 * non-blank actorId, ANONYMOUS must have none because the platform computes it. The
 * violation is reported on the property node {@code actorId}.
 */
@Documented
@Constraint(validatedBy = ActorIdMatchesActorTypeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActorIdMatchesActorType {
    /**
     * The default violation template. The validator replaces it with a more specific one.
     *
     * @return the message template
     */
    String message() default SignalConstants.ACTOR_ID_REQUIRED_MESSAGE;

    /**
     * The validation groups.
     *
     * @return the groups
     */
    Class<?>[] groups() default {};

    /**
     * The payload for clients of the Bean Validation API.
     *
     * @return the payload types
     */
    Class<? extends Payload>[] payload() default {};
}
