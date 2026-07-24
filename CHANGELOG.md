# Changelog
All notable changes to TrustGuard are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com).
Versioning follows [Semantic Versioning](https://semver.org).

---

## [Unreleased]

### Added
- Repository structure with branch protection (pre-B-001)
- detect-secrets pre-commit hook for secret scanning
- .env.example with all required secret key names
- BATCH_LOG.md for batch audit trail
- ADR.md with ADR-001 and ADR-002

### Security
- Branch protection enabled on main — all changes
  require pull request (Rule 19.1)
- detect-secrets baseline scanning on every commit
  (Rule 19.7)

### Added (B-001)
- CI pipeline with Jobs 1-5 (ci.yml)
- Release workflow with semver tagging (release.yml)
- pom.xml with all approved V1 dependencies
  (exact versions, Spring Boot 3.5.1, Java 21)
- Checkstyle configuration (no System.out/err/
  printStackTrace enforcement)
- SpotBugs, PMD, OWASP Dependency-Check,
  JaCoCo 80% coverage gate
- DEPENDENCY_STANDARDS.md with full V1
  approved dependency list

### Fixed (B-001 stabilization)
- PMD ruleset updated for PMD 7.3.0 compatibility
- OWASP Dependency-Check moved to main branch
  only to prevent CI timeout (DEV-001)
- detect-secrets unsupported flag removed (DEV-003)

### Security (B-001)
- All GitHub Actions pinned to verified commit
  SHAs (Rule 16.9)
- trivy-action upgraded from compromised v0.28.0
  to verified v0.36.0
- OWASP Dependency-Check configured with
  CVSS >= 7.0 build failure gate (main branch)
  
---