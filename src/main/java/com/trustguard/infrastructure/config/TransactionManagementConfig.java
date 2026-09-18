package com.trustguard.infrastructure.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicitly orders the transaction advisor at 0(outermost) so that TenantRlsAspect is guaranteed to
 * execute inside an already open transaction, never before it. Spring's default transaction advisor order
 * is Ordered.LOWEST_PRECEDENCE, which would place it innermost by default
 * the opp. of what Rule 4.1 layer 4 requires
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class TransactionManagementConfig {
}
