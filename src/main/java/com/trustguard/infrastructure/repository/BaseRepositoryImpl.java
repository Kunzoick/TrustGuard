package com.trustguard.infrastructure.repository;
import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BaseRepositoryImpl<T> implements BaseRepository<T> {
    private final EntityManager entityManager;
    private final Class<T> entityClass;
    private final String entityName;

    public BaseRepositoryImpl(EntityManager entityManager, Class<T> entityClass){
        this.entityManager= entityManager;
        this.entityClass= entityClass;
        jakarta.persistence.Entity entityAnnotation= entityClass.getAnnotation(jakarta.persistence.Entity.class);
        if(entityAnnotation== null){
            throw new IllegalArgumentException(entityClass.getName() + " is not a JPA entity. "
            + "BaseRepositoryImpl requires a class annotated with @Entity.");
        }
        this.entityName= (entityAnnotation.name() != null && !entityAnnotation.name().isEmpty())
        ? entityAnnotation.name()
        : entityClass.getSimpleName();
    }
    @Override
    public Optional<T> findById(UUID id){
        UUID tenantId= requireTenantId();
        TypedQuery<T> query= entityManager.createQuery("SELECT e FROM "+ entityName +
                " e WHERE e.id = :id AND e.tenantId = :tenantId", entityClass);
        query.setParameter("id", id);
        query.setParameter("tenantId", tenantId);
        return query.getResultStream().findFirst();
    }
    @Override
    public List<T> findAll(){
        UUID tenantId= requireTenantId();
        TypedQuery<T> query= entityManager.createQuery("SELECT e FROM " + entityName +
                " e WHERE e.tenantId = :tenantId", entityClass);
        query.setParameter("tenantId", tenantId);
        return query.getResultList();
    }
    @Override
    public T save(T entity){
        //Deliberately does not cross-check entity's own tenantId against TenantContextHolder before merging.
        //Layerr 4 rls(USING clause applies to INSERT/UPDATE by default when no separate WITH CHECK is defined)
        //rejects any row whose tenant_id does not match the active SET LOCAL value, so a mismatched write fails loudly at the DB rather than silenty
        //at this layer. Two ayer defence, not redundant code.
        requireTenantId();
        return entityManager.merge(entity);
    }

    private UUID requireTenantId(){
        TenantContext context= TenantContextHolder.get();
        if(context== null){
            throw new TenantContextMissingException("TenantContext is not set. BaseRepositoryImpl cannot scope quries without an authenticated tenant context.");
        }
        return UUID.fromString(context.tenantId().value());
    }
}
