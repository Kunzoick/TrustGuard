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

---