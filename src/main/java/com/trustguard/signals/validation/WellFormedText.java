package com.trustguard.signals.validation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.trustguard.signals.constants.SignalConstants;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * The annotated text must not contain the NUL character or an unpaired surrogate
 * (Ruling 66): PostgreSQL text and jsonb cannot store them. Valid surrogate pairs and
 * non-ASCII characters pass. A null value is valid. The message never contains the
 * rejected value.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = SignalConstants.WELL_FORMED_TEXT_REGEX)
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface WellFormedText {
    /**
     * The violation template.
     *
     * @return the message template
     */
    String message() default SignalConstants.TEXT_NOT_WELL_FORMED_MESSAGE;

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
