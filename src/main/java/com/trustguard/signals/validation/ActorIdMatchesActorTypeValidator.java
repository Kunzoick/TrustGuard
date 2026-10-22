package com.trustguard.signals.validation;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.dto.TrustEventDto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator of {@link ActorIdMatchesActorType} (Rule 6.3). Stateless and thread-safe.
 * Messages are fixed text and never contain the submitted value (Rule 16.6).
 */
public final class ActorIdMatchesActorTypeValidator implements
        ConstraintValidator<ActorIdMatchesActorType, TrustEventDto> {
    private static final String ACTOR_ID_NODE= "actorId";

    @Override
    public boolean isValid(TrustEventDto dto,  ConstraintValidatorContext context){
        if(dto== null || dto.actorType()== null){
            return true;
        }
        String actorId= dto.actorId();
        switch (dto.actorType()){
            case ANONYMOUS:
                if(actorId== null){
                    return true;
                }
                return reject(context, SignalConstants.ACTOR_ID_FORBIDDEN_MESSAGE);
            case USER:
            case SERVICE:
            default:
                if(actorId != null && !actorId.isBlank()){
                    return true;
                }
                return reject(context, SignalConstants.ACTOR_ID_REQUIRED_MESSAGE);
        }
    }
    private static boolean reject(ConstraintValidatorContext context, String template){
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(template).addPropertyNode(ACTOR_ID_NODE)
                .addConstraintViolation();
        return false;
    }
}
