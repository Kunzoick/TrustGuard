package com.trustguard.tenant.context;

/**
 * Rule 4.1 Layer 1 / Rule 4.4 — the only permitted access point
 * to the TenantContext ThreadLocal. No Spring bean; static by
 * design so it can be read from any thread-bound code without
 * dependency injection ceremony. clear() is the only permitted
 * cleanup path — callers must never touch a ThreadLocal directly.
 */
public final class TenantContextHolder {
    private static final ThreadLocal<TenantContext> CONTEXT=new ThreadLocal<>();
    private TenantContextHolder(){}

    public static void set(TenantContext context){
        CONTEXT.set(context);
    }
    public static TenantContext get(){
        return CONTEXT.get();
    }
    public static void clear(){
        CONTEXT.remove();
    }
}
