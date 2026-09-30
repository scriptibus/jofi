# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The Jofi image: Spring Boot backend with the frontend build as static resources, on a JRE with a
# JDK AOT cache created at build time (ADR-0003). The same image runs `app` and `worker` (ADR-0010).
# Build context is the repository root: `docker build -t jofi:local .` (see compose.yaml).

# --- 1. Frontend build -------------------------------------------------------------------------
FROM docker.io/library/node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe AS frontend
WORKDIR /build/frontend
# Corepack installs the exact pnpm version from package.json's packageManager field and verifies
# the tarball against the SHA-512 pinned there; pnpm then fetches its signature-checked native binary.
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# --- 2. Backend build (JDK 25) -----------------------------------------------------------------
# The build generates jOOQ code from the Flyway migrations on a real PostgreSQL (ADR-0030). docker build
# has no Docker daemon for Testcontainers, so this stage is the same pinned pgvector image as
# `jofi.postgresImage` in backend/gradle.properties (bump both together) with the Temurin JDK copied in,
# and the build starts that PostgreSQL itself, for the length of one RUN.
FROM docker.io/library/eclipse-temurin:25.0.4.1_1-jdk-resolute@sha256:570bd9a6e4e674d2743fb991b22b713557a50a46d7e9e5eb560e6849beb1ee43 AS jdk

FROM docker.io/pgvector/pgvector:0.8.6-pg18-trixie@sha256:78bf48b801e792f99e3ac62b5036fd3876e9be48afda16c1e331af1c75ceb2ff AS backend
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
WORKDIR /build/backend
COPY backend/ ./
# The SPA ships inside the jar; Spring Boot serves classpath:/static/ at the root.
COPY --from=frontend /build/frontend/dist/ bootstrap/src/main/resources/static/
# Throwaway database: trust auth, listens on loopback only, removed at the end of the RUN.
ENV PGDATA=/tmp/codegen-pgdata \
    JOFI_CODEGEN_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres \
    JOFI_CODEGEN_JDBC_USER=postgres
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    mkdir -p "$PGDATA" \
    && chown postgres:postgres "$PGDATA" \
    && gosu postgres initdb --auth=trust --username=postgres \
    && gosu postgres pg_ctl --wait --options="-c listen_addresses=127.0.0.1 -k /tmp" start \
    && ./gradlew --no-daemon :bootstrap:bootJar \
    && gosu postgres pg_ctl --wait --mode=fast stop \
    && rm -rf "$PGDATA" \
    && mkdir /build/app \
    && find bootstrap/build/libs -name 'bootstrap-*.jar' ! -name '*-plain.jar' -exec cp {} /build/app.jar \; \
    && java -Djarmode=tools -jar /build/app.jar extract --destination /build/app

# --- 3. Runtime (JRE 25) -----------------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25.0.4.1_1-jre-resolute@sha256:b8e5a7403fd1e1fd8cd09118f8a808ac0482736bef2946e89f261efbe71c52d8

LABEL org.opencontainers.image.title="Jofi" \
      org.opencontainers.image.description="Self-hosted, AI-assisted job application manager" \
      org.opencontainers.image.source="https://github.com/scriptibus/jofi" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"

# Fixed, unprivileged IDs so volume ownership is predictable across hosts.
RUN groupadd --system --gid 10001 jofi \
    && useradd --system --uid 10001 --gid jofi --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin jofi \
    && mkdir -p /data \
    && chown jofi:jofi /data

WORKDIR /opt/jofi
# The application stays owned by root: the jofi user can read it but never modify it.
COPY --from=backend /build/app/ ./
COPY --chmod=755 backend/docker/healthcheck.sh /usr/local/bin/jofi-healthcheck

# Training run for the JDK AOT cache (JEP 483/514/515): starts the context, then exits on refresh.
# It must run on this exact JVM and the extracted jar layout, so it happens in the runtime stage.
# No database exists at build time: Flyway is off and the datasource gets a placeholder URL and
# password (the pool connects lazily; startup refuses a missing password). The cache only records
# which classes were loaded and linked.
RUN java -XX:AOTCacheOutput=app.aot \
      -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false \
      -DJOFI_DB_URL=jdbc:postgresql://localhost:5432/aot-training \
      -DJOFI_DB_PASSWORD=aot-training-placeholder \
      -jar app.jar

VOLUME ["/data"]
# Numeric so the non-root check works without resolving names (jofi:jofi).
USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD ["jofi-healthcheck"]

ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
