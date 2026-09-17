package com.trustguard.infrastructure.config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ADR-006- dedicated dataSource & jdbcTemplate for auth resolver role. This connection pool is used mainly by the KeyLookupService & KeyRevocationService
 * for their PostgreSQL fallback paths, which must execute before TenantContext exists & therefore cannot go through the
 * RLS-protected trustguard_app connection
 *
 * Deluberately not a JPA entityManager- the two queries this pool serves are simple, single-purpose SELECTs.
 * it keeps the bypass surface minimal and explicit, per the ADR-006 ruling that s second EntityManager would be more infrastructure than the problem requires
 * Pool is intentionally small(min 2, max 5)- these queries are fast and infrequent relative to the main application pool.
 */
@Configuration
public class AuthResolverDataSourceConfig {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int minimumIdle;
    private final int maximumPoolSize;
    private final String poolName;

    public AuthResolverDataSourceConfig(
            @Value("${trustguard.auth-resolver.datasource.jdbc-url:jdbc:postgresql://localhost:5432/trustguard}")
            String jdbcUrl,
            @Value("${trustguard.auth-resolver.datasource.username:trustguard_authresolver}")
            String username,
            @Value("${trustguard.auth-resolver.datasource.password:trustguard_authresolver_dev}")
            String password,
            @Value("${trustguard.auth-resolver.datasource.minimum-idle:2}")
            int minimumIdle,
            @Value("${trustguard.auth-resolver.datasource.maximum-pool-size:5}")
            int maximumPoolSize,
            @Value("${trustguard.auth-resolver.datasource.pool-name:auth-resolver-pool}")
            String poolName) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.minimumIdle = minimumIdle;
        this.maximumPoolSize = maximumPoolSize;
        this.poolName = poolName;
    }

    @Bean
    public DataSource authResolverDataSource(){
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMinimumIdle(minimumIdle);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setPoolName(poolName);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
    @Bean(name= "authResolverJdbcTemplate")
    public JdbcTemplate authResolverJdbcTemplate(DataSource authResolverDataSource){
        return new JdbcTemplate(authResolverDataSource);
    }
}
