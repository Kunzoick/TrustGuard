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
 * The annotated event payload must be serializable as canonical JSON, must not exceed
 * 32768 UTF-8 bytes in canonical form, and must not contain NUL or unpaired surrogates in
 * any string value or key (Rules 6.4 and 16.5, Rulings 52 and 66). A null payload is valid
 * (Ruling 57). An oversize payload is reported with the template
 * {@link SignalConstants#PAYLOAD_TOO_LARGE_MESSAGE}, every other failure with a different
 * one (Ruling 67). Messages never contain payload content.
 */
@Documented
@Constraint(validatedBy = ValidPayloadValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPayload {

    /**
     * The default violation template. The validator replaces it with a more specific one.
     *
     * @return the message template
     */
    String message() default SignalConstants.PAYLOAD_UNSUPPORTED_MESSAGE;

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