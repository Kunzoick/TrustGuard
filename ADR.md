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

=== ADR-003 ===
Title:  Adopt Spring Boot 4.1.x — supersedes
        Rule 2.3's Spring Boot 3.5.1 pin
Date:   2026-07-24
Batch:  Raised during B-002, resolves before B-003
Status: ACCEPTED

=== CONTEXT ===
Spring Boot 3.5 reached end-of-life on June 30
2026 — approximately three weeks before this
project began writing code. It no longer receives
security patches. Spring Initializer no longer
offers it as an option. Any CVE discovered in
Spring Framework 6.x after EOL will have no patch
available, permanently violating Principle 0.3
(security before convenience) and Rule 16.9
(supply chain security).

Spring Boot 4.0 was released November 2025.
Spring Boot 4.1 was released June 2026.
Both are built on Spring Framework 7 with a
Java 17 minimum (Java 21 is supported and
preferred — no change to our Java version).

=== DECISION ===
Adopt Spring Boot 4.1.x (latest stable patch)
as the framework version for TrustGuard V1.
Rule 2.3 is superseded — Spring Boot 3.5.1
is replaced with Spring Boot 4.1.x everywhere
it appears in the Engineering Contract,
PROJECT_STATE, and pom.xml.

This decision is forced by EOL, not by
preference. Staying on 3.5.1 violates
Principle 0.3 and Rule 16.9 before we have
written a single line of business logic.

=== ALTERNATIVES CONSIDERED ===
Stay on Spring Boot 3.5.1:
  Rejected. EOL means no security patches.
  Any CVE in Spring Framework 6.x is permanent
  technical debt with no resolution path.
  Violates Principle 0.3 unconditionally.

Spring Boot 4.0.x:
  Rejected in favor of 4.1.x. 4.1 is current
  stable. 4.0 will reach EOL sooner. No reason
  to adopt an older current version when 4.1
  is available.

=== CONSEQUENCES ===
Positive:
  - Active security patch track restored.
  - Spring Initializer works normally.
  - Project is on a supported framework
    from day one.

Negative:
  - Every explicitly pinned dependency in
    pom.xml must be re-verified for Spring
    Framework 7 compatibility before B-003.
  - This is a mandatory pre-B-003 task.
  - Affected dependencies to verify:
      jjwt 0.12.6
      resilience4j-spring-boot3 2.2.0
      springdoc-openapi-starter-webmvc-ui 2.6.0
      archunit-junit5 1.3.0
      logstash-logback-encoder 8.0
      testcontainers 1.20.4
      jqwik 1.9.1

=== CONTRACT RULE AFFECTED ===
Rule 2.3 — Technology stack.
Old text: Spring Boot: 3.5.1
New text: Spring Boot: 4.1.x (latest stable patch
          at time of B-003 Spring Initializer
          generation)

=== RELATED ADRs ===
ADR-001, ADR-002 — unaffected