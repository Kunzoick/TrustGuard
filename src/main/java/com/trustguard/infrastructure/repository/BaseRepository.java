package com.trustguard.infrastructure.repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Layer 3 fallback for custom query logic outside Spring data. Deliberately minimal per brief
 * Do not over-engineer. TenantId is never a parameter her; it is read internally from TenantContextHolder so callers cannot bypass scoping.
 *
 * Any entity T used with BaseRepositoryImpl must expose a property named exactly "tenantId"
 * the JPQL in BaseRepositoryImpl assumes this convention
 */
public interface BaseRepository<T> {
    Optional<T> findById(UUID id);
    List<T> findAll();
    T save(T entity);
}
