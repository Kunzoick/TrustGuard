package com.trustguard.signals.validation;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.util.CanonicalJsonSerializer;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator of {@link ValidPayload}. Stateless and thread-safe. The payload is serialized
 * once. The first failure wins: oversize, then an unsupported value, then bad text. The
 * serializer's depth bound runs before the tree walk, so the walk is never reached with a
 * deep or self-referencing structure. Messages are fixed templates and never contain
 * payload content (Rule 16.6).
 */
public final class ValidPayloadValidator implements ConstraintValidator<ValidPayload, Map<?, ?>> {

    private static final Pattern WELL_FORMED_TEXT = Pattern.compile(SignalConstants.WELL_FORMED_TEXT_REGEX);

    @Override
    public boolean isValid(Map<?, ?> payload, ConstraintValidatorContext context) {
        if (payload == null) {
            return true;
        }
        String failure = firstFailure(payload);
        if (failure == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(failure).addConstraintViolation();
        return false;
    }

    private static String firstFailure(Map<?, ?> payload) {
        byte[] canonical;
        try {
            canonical = CanonicalJsonSerializer.serializeToBytes(payload);
        } catch (CanonicalJsonSerializer.OutputLimitExceededException ex) {
            return SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE;
        } catch (IllegalArgumentException ex) {
            return SignalConstants.PAYLOAD_UNSUPPORTED_MESSAGE;
        }
        if (canonical.length > SignalConstants.MAX_PAYLOAD_BYTES) {
            return SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE;
        }
        if (hasIllegalText(payload)) {
            return SignalConstants.PAYLOAD_TEXT_MESSAGE;
        }
        return null;
    }

    private static boolean hasIllegalText(Object value) {
        if (value instanceof String text) {
            return !WELL_FORMED_TEXT.matcher(text).matches();
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (hasIllegalText(entry.getKey()) || hasIllegalText(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (hasIllegalText(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}