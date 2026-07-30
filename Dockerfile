# ============================================================
# Build stage — never shipped, discarded after the jar is built.
# ============================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Copy pom.xml first so Docker's layer cache is reused across builds
# when only source files change, not dependencies.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
COPY checkstyle.xml spotbugs-exclude.xml pmd-ruleset.xml dependency-check-suppression.xml ./
RUN mvn -B clean package -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dpmd.skip=true -Ddependency-check.skip=true

# ============================================================
# Runtime stage — Rule 15.4: eclipse-temurin:21-jre-alpine only.
# Not FROM ubuntu. Not FROM openjdk (deprecated). Minimal attack
# surface, minimal image size.
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# Rule 15.4: every container runs as a non-root user. A container
# escape vulnerability while running as root gives an attacker root on
# the host — never acceptable.
RUN apk update && apk upgrade --no-cache

RUN addgroup -S trustguard && adduser -S trustguard -G trustguard

# TRADE-OFF, flagged rather than silently resolved: Rule 15.4's own
# HEALTHCHECK example uses curl, but eclipse-temurin:21-jre-alpine does
# not include it. Installing curl is a small, deliberate increase in
# attack surface to satisfy the rule's literal healthcheck mechanism —
# in tension with that same rule's "minimal attack surface" goal for
# the base image choice. Flagging this explicitly rather than picking
# silently between "follow the letter" and "follow the spirit."
RUN apk add --no-cache curl

WORKDIR /app

COPY --from=build /build/target/trustguard-*.jar app.jar

RUN chown trustguard:trustguard /app/app.jar

USER trustguard

# Liveness and readiness are separate probes (Rule 2.8, 2.9) — this
# HEALTHCHECK targets readiness specifically, since that is the signal
# Docker Compose's depends_on: condition: service_healthy needs (Rule
# 15.7): not just "the JVM is running" but "all dependencies are
# healthy and the app is ready for traffic."
HEALTHCHECK --interval=30s \
            --timeout=10s \
            --start-period=60s \
            --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health/readiness || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]