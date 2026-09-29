# Changelog

All notable changes to TrustGuard are documented here.
Format follows Keep a Changelog.
Versions follow Semantic Versioning.

## [Unreleased]

### Added
- Flyway migrations V1-V6: tenant, project, API key,
  revocation, admin user, security event, and ShedLock
  tables (B-004)
- RLS policies with FORCE ROW LEVEL SECURITY on all
  tenant-scoped tables (B-004)
- WITH CHECK INSERT isolation policies on projects,
  api_keys, revoked_keys (B-004 V6)
- trustguard_app role with least-privilege grants (B-004)
- TenantContext record implementing Rule 4.1 Layer 1 (B-005)
- TenantContextHolder ThreadLocal with enforced cleanup (B-005)
- TenantRlsAspect: fires set_config() in every transaction
  for PostgreSQL RLS enforcement (Rule 4.1 Layer 4) (B-005)
- TransactionManagementConfig: explicit AOP ordering (B-005)
- BaseRepository / BaseRepositoryImpl: Layer 3 tenant
  scoping with correct JPA entity name resolution (B-005)
- V5 migration: trustguard_app LOGIN for RLS testing (B-005)
- V6 migration: WITH CHECK INSERT isolation (B-005)
- Rule 4.2 Vector 1 integration test live and passing (B-005)
- TenantContextArchTest: SUPPORTS/NOT_SUPPORTED banned (B-005)
- ModuleDependencyTest: infrastructure→tenant and
  tenant→shared dependencies now correctly permitted (B-005)

### Changed
- SpringDoc api-docs and swagger-ui disabled in
  production profile (B-004)
- amqp-client upgraded from 5.30.0 to 5.33.1
  (CVE-2026-63337, CVE-2026-69219, CVE-2026-69220)
- DEPENDENCY_STANDARDS.md updated to Spring Boot 4.1.0
  and Resilience4j 2.4.0

### Fixed
- security_events created in V3 rather than planned V12
  (B-007 dependency requirement)
- api_keys immutability enforced via separate revoked_keys
  table per ADR-004 (original brief contradicted itself)

## [0.1.0-SNAPSHOT] — Phase 1 Complete

### Added
- Repository with branch protection and CI pipeline (B-001)
- Domain primitives: value objects, enums, exception
  hierarchy (B-002)
- Spring Boot application shell with TLS gate, thread
  pools, health checks, ArchUnit module boundaries (B-003)
- Docker Compose stack: PostgreSQL 16, Redis 7.4,
  RabbitMQ 4.0 (B-003)
- Cross-tenant leak test skeletons for Vectors 1-3 (B-003)