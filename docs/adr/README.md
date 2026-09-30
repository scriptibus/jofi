<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Architecture decision records

New ADR: copy `template.md` to the next free number. Supersede instead of rewriting accepted ADRs.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-backend-in-kotlin-with-spring-boot-on-the-jvm.md) | Backend in Kotlin with Spring Boot on the JVM | accepted |
| [0003](0003-jvm-with-aot-cache-no-graalvm-native-image-for-now.md) | JVM with AOT cache, no GraalVM native image for now | accepted |
| [0004](0004-hexagonal-architecture-enforced-by-gradle-modules.md) | Hexagonal architecture enforced by Gradle modules | accepted |
| [0005](0005-bounded-contexts-as-packages-verified-by-spring-modulith.md) | Bounded contexts as packages, verified by Spring Modulith | accepted |
| [0006](0006-clean-code-rules-enforced-by-detekt-ktlint-and-architecture.md) | Clean-code rules enforced by detekt, ktlint and architecture tests | accepted |
| [0007](0007-use-detekt-20-pre-release-until-it-is-stable.md) | Use detekt 2.0 pre-release until it is stable | accepted |
| [0008](0008-postgresql-with-pgvector-and-pg_trgm.md) | PostgreSQL with pgvector and pg_trgm | accepted |
| [0009](0009-flyway-migrations-and-jooq-no-jpa.md) | Flyway migrations and jOOQ, no JPA | accepted |
| [0010](0010-jobrunr-for-background-jobs-in-a-separate-worker-container.md) | JobRunr for background jobs in a separate worker container | accepted |
| [0011](0011-spring-ai-behind-our-own-ports.md) | Spring AI behind our own ports | accepted |
| [0012](0012-one-mcp-tool-surface-for-the-built-in-chat-and-external-clie.md) | One MCP tool surface for the built-in chat and external clients | accepted |
| [0013](0013-knowledge-as-markdown-in-a-git-repository.md) | Knowledge as Markdown in a git repository | accepted |
| [0014](0014-apache-tika-for-document-extraction.md) | Apache Tika for document extraction | accepted |
| [0015](0015-pdf-generation-with-htmlcss-templates-and-gotenberg.md) | PDF generation with HTML/CSS templates and Gotenberg | accepted |
| [0016](0016-openapi-contract-with-generated-typescript-client.md) | OpenAPI contract with generated TypeScript client | accepted |
| [0017](0017-single-user-authentication-and-encrypted-secrets.md) | Single-user authentication and encrypted secrets | accepted |
| [0018](0018-frontend-react-vite-tanstack-paraglide-pwa-from-m0.md) | Frontend: React, Vite, TanStack, Paraglide, PWA from M0 | accepted |
| [0019](0019-design-system-react-aria-components-tailwind-v4-and-our-own.md) | Design system: React Aria Components, Tailwind v4 and our own tokens | accepted |
| [0020](0020-visual-direction-b-stall-with-user-selectable-accent.md) | Visual direction B · Stall with user-selectable accent | accepted |
| [0021](0021-review-lenses-in-ci.md) | Review lenses in CI | accepted |
| [0022](0022-trunk-based-development-one-issue-one-agent-one-pr.md) | Trunk-based development: one issue, one agent, one PR | accepted |
| [0023](0023-merge-gate-low-risk-prs-may-auto-merge.md) | Merge gate: low-risk PRs may auto-merge | accepted |
| [0024](0024-quality-pipeline-with-identical-local-and-ci-commands.md) | Quality pipeline with identical local and CI commands | accepted |
| [0025](0025-security-and-supply-chain-pipeline.md) | Security and supply-chain pipeline | accepted |
| [0026](0026-agpl-30-licence-and-dependency-licence-allowlist.md) | AGPL-3.0 licence and dependency licence allowlist | accepted |
| [0027](0027-ai-verifiable-ui-with-a-headless-browser-and-a-fake-ai-provi.md) | AI-verifiable UI with a headless browser and a fake AI provider | accepted |
| [0028](0028-always-use-current-versions-and-current-documentation.md) | Always use current versions and current documentation | accepted |
| [0029](0029-docker-compose-deployment-localhost-by-default.md) | Docker Compose deployment, localhost by default | accepted |
| [0030](0030-persistence-baseline-codegen-and-append-only-changelog.md) | Persistence baseline: build-time jOOQ codegen and an append-only changelog | accepted |
| [0031](0031-general-code-review-with-the-code-review-plugin-on-the-workflow-token.md) | General code review with the code-review plugin on the workflow token | accepted |
| [0032](0032-cross-context-ports-in-an-open-shared-kernel.md) | Cross-context ports live in an open `shared` kernel | accepted |
| [0033](0033-api-contract-pipeline-committed-spec-generated-client.md) | API contract pipeline: committed spec, build-time client, oasdiff with a human override | accepted |
| [0034](0034-ssrf-guard-with-pinned-dns-and-an-allowlist-for-ai-endpoints.md) | SSRF guard with pinned DNS, and an allowlist for AI endpoints | accepted |
| [0035](0035-login-sessions-csrf-backoff-and-the-master-keyset.md) | Login, sessions, CSRF, backoff and the master keyset | accepted |
| [0036](0036-e2e-stack-with-a-wire-level-fake-ai-provider.md) | e2e stack with a wire-level fake AI provider | accepted |
| [0037](0037-frontend-shell-routing-auth-guard-and-pwa-caching.md) | Frontend shell: code-based routes, a session guard, and a shell-only service worker | accepted |
| [0038](0038-jobrunr-job-store-worker-profile-and-job-log.md) | JobRunr job store, worker profile and job log | accepted |
| [0039](0039-server-enforced-two-step-confirmation.md) | Server-enforced two-step confirmation for deletes and outward-facing actions | accepted |
| [0040](0040-ai-providers-through-spring-ai-and-vendor-sdk-cores-on-the-guarded-transport.md) | AI providers through Spring AI and the vendor SDK cores on the guarded transport | accepted |
| [0041](0041-context-contracts-inbound-ports-input-validation-and-versions.md) | Context contracts: inbound ports, validation as values, optimistic versions | accepted |
| [0042](0042-backup-archive-format-restore-and-the-master-keyset.md) | Backup archive format, restore, and the master keyset in backups | accepted |
| [0043](0043-ai-gateway-never-send-filter-and-cost-meter.md) | The AI gateway, the "never send to AI" filter and the cost meter | accepted |
| [0044](0044-application-status-pipeline-transition-matrix-and-history.md) | Application status pipeline: transition matrix, decline reason and history | accepted |
| [0045](0045-dated-provider-privacy-info.md) | Dated provider privacy info with quoted sources | accepted |
| [0046](0046-application-sources-description-snapshots-hash-and-freeze.md) | Application sources and job description snapshots: content hash and freezing on applying | accepted |
| [0047](0047-sanitised-markdown-rendering-in-the-frontend.md) | Sanitised Markdown rendering in the frontend | accepted |
| [0048](0048-interviews-scheduled-times-as-instant-and-planning-zone.md) | Interviews and calls: an aggregate of their own, scheduled as an instant plus the zone planned in | accepted |
| [0049](0049-tasks-absolute-due-buckets-single-link-and-suggestion-identity.md) | Tasks: absolute due buckets, one clearable link, suggestions identified by rule and key | accepted |
| [0050](0050-saved-views-versioned-filter-documents-and-application-settings.md) | Saved views as versioned filter documents read tolerantly; application settings as an optional single row | accepted |
