# TrustGuard — Dependency Standards

This document is the source of truth for every dependency permitted in
`pom.xml`. A dependency added without a corresponding entry here, and
without both decision-criteria questions answered in the PR description,
fails review (Rule 20.2).

---

## 1. Approved Dependencies — V1

| Dependency | Version | Justified by |
|---|---|---|
| Spring Boot (parent BOM) | 3.5.1 | Non-negotiable foundation (Rule 2.3) |
| Spring Security | BOM-managed | Non-negotiable — admin auth, API key filter chain |
| Spring Data JPA | BOM-managed | Non-negotiable — PostgreSQL persistence |
| Spring Data Redis (Lettuce) | BOM-managed | Non-negotiable — Redis client (Rule 2.3) |
| Spring AMQP | BOM-managed | RabbitMQ integration, proven in Pipeline project |
| PostgreSQL JDBC driver | BOM-managed | Non-negotiable |
| Flyway (core + postgresql) | BOM-managed | Non-negotiable, proven; `ddl-auto: none` always |
| JJWT (api/impl/jackson) | 0.12.6 | Admin JWT signing (Rule 16.7), proven in previous projects |
| Resilience4j (spring-boot3) | 2.2.0 | Circuit breakers required by Rule 9.6 |
| Logstash Logback Encoder | 8.0 | Structured JSON logging required by Rule 14.4 |
| Testcontainers (BOM) | 1.20.4 | Integration testing required by Rule 18.7 |
| jqwik | 1.9.1 | Property-based testing required by Rule 18.3 |
| ArchUnit (junit5) | 1.3.0 | Module boundary enforcement required by Rule 17.1 |
| Micrometer (prometheus registry) | BOM-managed | Metrics required by Rule 14.3 |
| Lombok | BOM-managed | Boilerplate reduction, no runtime cost |
| SpringDoc OpenAPI (webmvc-ui) | 2.6.0 | OpenAPI generation required by Rule 13.8 |

"BOM-managed" means the exact version is pinned transitively by the
Spring Boot 3.5.1 dependency-management BOM. This still satisfies
Rule 20.1 — the resolved version is a single, reproducible value, not a
floating range.

---

## 2. Decision Criteria for New Dependencies (Rule 20.3)

Before any new dependency is proposed, the PR description must answer
**all three** questions explicitly:

1. **Value:** Does this dependency provide enough long-term value to
   justify the maintenance and security cost?
2. **Coverage:** Does the Java standard library or an already-approved
   dependency cover this need adequately?
   - If **yes** → do not add the dependency.
3. **Replaceability:** If this dependency disappears or becomes hostile
   in 2 years, how hard is it to replace? High replacement cost requires
   stronger justification, not just a positive value assessment.

If Question 1 is answered "no," the dependency is not added — write the
code instead.

---

## 3. Utility Library Policy (Rule 20.4)

| Library | Policy |
|---|---|
| Guava | Avoid by default. Not hard-banned. Requires explicit justification that the JDK does not cover the specific need. |
| Apache Commons Lang | Same policy as Guava. |
| Apache Commons IO | Same policy as Guava. |
| Vavr / functional libraries | Requires an ADR before adoption — changes how the whole codebase reads. |

---

## 4. Mapping Library Policy (Rule 20.5)

- **Manual mapping is the V1 default.**
- **MapStruct** may be adopted only when mapping complexity is measurable
  (DTO count, mapping-error frequency in review) **and** an ADR documents
  the decision and evidence. Adopted via ADR, never via a plain
  dependency PR.
- **ModelMapper** and other reflection-based mappers are **rejected
  permanently** — reflection-based mapping hides errors that manual
  mapping surfaces at compile time.

---

## 5. Automatic Rejection Criteria (Rule 20.6)

A dependency is rejected automatically if any of the following are true:

- Not used by at least one known organization in the same domain, no
  regular commits in the last 6 months, no clear maintenance policy, or
  not published to Maven Central.
- License is GPL, AGPL, SSPL, Commons Clause, or proprietary.
- Last commit more than 18 months ago and not explicitly documented as
  "maintenance-only" by the maintainers.
- Pulls in more than 5 transitive dependencies not already on this list
  (`mvn dependency:tree` is run before every proposal).
- Has a known unpatched CVSS >= 7.0 vulnerability with no fix available.
- Fewer than 1000 GitHub stars and no major corporate sponsor.

---

## 6. License Compliance (Rule 20.7)

| Status | Licenses |
|---|---|
| Permitted | MIT, Apache 2.0, BSD 2-Clause, BSD 3-Clause, ISC |
| Requires legal review | LGPL (only if used dynamically, not statically linked) |
| Rejected | GPL, AGPL, SSPL, Commons Clause, proprietary, no license |

License check runs **when a dependency is added**, not after, not at
release time. OWASP Dependency-Check reports license information as
part of its output — this is reviewed as part of every dependency
addition.

TrustGuard's own license: MIT or Apache 2.0 (to be confirmed before V1
release). All dependencies must be compatible with the chosen license.

---

## 7. Version Pinning (Rule 20.1)

No version ranges. No `LATEST`. No `RELEASE`. Every dependency not
managed by the Spring Boot BOM has an exact version declared as a
Maven property in `pom.xml` (see the properties block at the top of
the file) so that every pinned version is visible in one place.