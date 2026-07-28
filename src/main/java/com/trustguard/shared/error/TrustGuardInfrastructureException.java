package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Base class for infrastructure failures-> redis unreachable, PostgreSQL slow, RabbitMQ down- maps to a 5xx response at the API layer
 * as distinct from TrustGuardException's 4xx business rule violations
 */

public abstract class TrustGuardInfrastructureException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;

    protected TrustGuardInfrastructureException(String message, ErrorCode code, boolean retryable){
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
    protected TrustGuardInfrastructureException(String message, Throwable cause, ErrorCode code, boolean retryable){
        super(message, cause);
        this.code= code;
        this.retryable= retryable;
    }
    public ErrorCode code(){
        return code;
    }
    public boolean retryable(){
        return retryable;
    }
}
