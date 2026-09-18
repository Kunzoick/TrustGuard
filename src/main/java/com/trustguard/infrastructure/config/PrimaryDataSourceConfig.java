package com.trustguard.infrastructure.config;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;

/**
 * Explicitly declares the primary DataSOurce bean so Spring Boot's autoConfigure For Flyway,
 * JPA and health checks uses the main trust_guard connection rather than the authResolverDateSource.
 * Without @primary, SpringBoot picks one of the two DataSource beans arbitrarily and flyyway ends up
 * connecting to the wrong database.
 */
@Configuration
public class PrimaryDataSourceConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties){
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
