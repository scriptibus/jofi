# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The Jofi image: Spring Boot backend with the frontend build as static resources, on a JRE with a
# JDK AOT cache created at build time (ADR-0003). The same image runs `app` and `worker` (ADR-0010).
# Build context is the repository root: `docker build -t jofi:local .` (see compose.yaml).

# --- 1. Frontend build -------------------------------------------------------------------------
FROM docker.io/library/node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe AS frontend
WORKDIR /build/frontend
# Corepack installs the exact pnpm version from package.json's packageManager field.
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# --- 2. Backend build (JDK 25) -----------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25.0.4.1_1-jdk-resolute@sha256:570bd9a6e4e674d2743fb991b22b713557a50a46d7e9e5eb560e6849beb1ee43 AS backend
WORKDIR /build/backend
COPY backend/ ./
# The SPA ships inside the jar; Spring Boot serves classpath:/static/ at the root.
COPY --from=frontend /build/frontend/dist/ bootstrap/src/main/resources/static/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon :bootstrap:bootJar \
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
# No database exists at build time: Flyway is off and the datasource gets a placeholder URL (the pool
# connects lazily). The cache only records which classes were loaded and linked.
RUN java -XX:AOTCacheOutput=app.aot \
      -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false \
      -DJOFI_DB_URL=jdbc:postgresql://localhost:5432/aot-training \
      -jar app.jar

VOLUME ["/data"]
# Numeric so the non-root check works without resolving names (jofi:jofi).
USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD ["jofi-healthcheck"]

ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
