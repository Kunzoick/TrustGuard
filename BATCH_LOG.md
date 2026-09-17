# TrustGuard Batch Log

## Phase 1 — Foundation

- B-001: Repository and CI Foundation
  — APPROVED (v2) — 2026-07-24

- B-002: Domain Primitives
  — APPROVED (v2) — 2026-07-27

- B-003: Spring Boot Application Shell
  — APPROVED (v2) — 2026-07-29

## Phase 2 — Tenant and Security Foundation

- B-004: Flyway Schema V1-V6
  — APPROVED (v2) — 2026-07-30
  — 2 CF items fixed (FORCE RLS, policy naming)
  — ADR-004 written (revocation model)
  — security_events moved V12→V3 for B-007 dependency
  — WITH CHECK gap caught post-approval, fixed in V6

- B-005: Tenant Context and RLS Enforcement
  — APPROVED (v2) — 2026-08-03
  — PR merged to main — 2026-08-25
  — Commit: 7efc0a6
  — 3 CF items fixed (constructor order, ArchUnit API,
    Flyway placeholder wiring)
  — Rule 4.2 Vector 1 live and passing
  — V5 migration added for trustguard_app LOGIN
  — DEPENDENCY_STANDARDS.md staleness resolved
  — Post-approval corrections applied before merge:
    * WITH CHECK policies added (V6 migration)
    * set_config false→true in TenantIsolationTest
    * BaseRepositoryImpl entity name resolution fixed
    * ModuleDependencyTest updated for infrastructure→
      tenant and tenant→shared dependencies
    * TenantContextArchTest allowEmptyShould added
    * amqp-client upgraded 5.30.0→5.33.1
      (CVE-2026-63337, CVE-2026-69219, CVE-2026-69220)
    * RlsEnforcementTest moved to tenant package,
      ProbeService registered via @TestConfiguration
    * sampleContext() UUID mismatch fixed
    * V5 Flyway placeholder replaced with fixed
      dev password trustguard_app_dev
    * Docker build context issue resolved by moving
      project to WSL native filesystem

## PRODUCT DECISIONS (not batch items)

- PRODUCT NOTE (B-005 session): Tier-based billing
  model documented as DRAFT for V2 Phase 5.
  STANDARD 7,000 free credits / $0.10 per credit /
  $75/month. FULL 3,000 free credits / $0.30 per
  credit / $180/month. Tentative — review before
  V2 Phase 5 begins.