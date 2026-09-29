package com.trustguard.shared.error;

import com.trustguard.shared.enums.ErrorCode;

/**
 * Thrown when tenantRlsAspect or BaseRepositoryImpl attempts to enforce tenant isolation but TenantContext is missing.
 * THIS INDICATES A MISSING OR BYPASSED AUTHENTICATION step which is never a normal business-flow condition.
 */
public class TenantContextMissingException extends TrustGuardInfrastructureException{
    public TenantContextMissingException(String message){
        super(message, ErrorCode.TENANT_CONTEXT_MISSING, false);
    }
}
