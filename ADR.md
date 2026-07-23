# TrustGuard — Architecture Decision Records

All architectural decisions are recorded here permanently.
Closed ADRs are never reopened.
New ADRs are appended at the bottom.

---

## ADR-001
Title:  PostgreSQL over MariaDB
Date:   2026-06-01
Status: ACCEPTED

### Context
TrustGuard requires Row Level Security for tenant
isolation at the database layer. MariaDB does not
support RLS. PostgreSQL 16 does.

### Decision
PostgreSQL 16 is the only permitted database engine
for TrustGuard V1 and all future versions.

### Consequences
Positive: Native RLS support satisfies Rule 4.1 Layer 4.
Negative: Developers familiar only with MySQL/MariaDB
face a learning curve.

---

## ADR-002
Title:  Redis as primary read cache
Date:   2026-06-02
Status: ACCEPTED

### Context
checkRisk() must complete within computationTimeoutMs.
Reading actor state from PostgreSQL on every request
adds unacceptable latency. A fast read layer is required.

### Decision
Redis 7.4 is the primary read cache for actor state,
tenant configuration, and API key lookups.
PostgreSQL remains the source of truth.
Redis holds only data that can be rebuilt from PostgreSQL.

### Consequences
Positive: Sub-millisecond actor state reads satisfy
Rule 8.3 and Rule 3.4 latency requirements.
Negative: Redis unavailability triggers DEGRADED state
per Rule 9.1. Requires AOF persistence per Rule 8.2.

---