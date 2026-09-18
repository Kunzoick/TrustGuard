package com.trustguard.infrastructure.aspect;
import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Rule 4.1 Layer 4 — fires set_config('app.tenant_id', value, true)
 * (transaction-scoped, equivalent to SET LOCAL) at the start of
 * every @Transactional service method. Ordered to run inside the
 * transaction boundary — see TransactionManagementConfig.
 *
 * Uses set_config() rather than a literal SET LOCAL string because
 * PostgreSQL's SET family of statements does not accept bind
 * parameters over the extended query protocol. set_config() is a
 * regular function call and binds safely.
 *
 * Never catches exceptions from the join point — they propagate
 * naturally per the brief.
 */
@Aspect
@Component
@Order(1)
public class TenantRlsAspect {
    private final EntityManager entityManager;
    public TenantRlsAspect(EntityManager entityManager){
        this.entityManager= entityManager;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) "
            + "&& within(com.trustguard..*Service+)")
    public Object enforceTenantIsolation(ProceedingJoinPoint joinPoint) throws Throwable{
        TenantContext context= TenantContextHolder.get();
        if(context== null){
            throw new TenantContextMissingException("TenantContext is not set. Cannot enforce RLS without an "+
                    "authentication tenant context. Method: "+ joinPoint.getSignature().toShortString());
        }
        if(!TransactionSynchronizationManager.isActualTransactionActive()){
            throw new TransactionRequiredException("TenantRlsAspect fired with no active transaction. This means "
            + "@Transactional is missing on the calling method, or the aspect ordering is broken. Method: "+ joinPoint.getSignature().toShortString());
        }
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tenantId, true)")
                .setParameter("tenantId", context.tenantId().value()).getSingleResult();
        return joinPoint.proceed();
    }
}
