<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Jofi backend: agent guide

Kotlin 2.4 + Spring Boot 4.1 on JDK 25, hexagonal architecture, Gradle 9.8 (Kotlin DSL).
Spec: `docs/spec/04-tech-stack-proposal.md` (sections 3, 4.1, 4.2, 4.5, 4.6, 4.6a, 4.10).

## Commands (run from `backend/`)

| What | Command |
|---|---|
| Everything CI checks (format, detekt, tests, architecture, coverage, licenses) | `./gradlew check` |
| Fix formatting and SPDX headers | `./gradlew spotlessApply` |
| One module's quality loop | `./gradlew :application:check` |
| Architecture rules only | `./gradlew :architecture-tests:test` |
| Run the app against a throwaway PostgreSQL (http://localhost:8080/api/auth/session; first run, then log in) | `./gradlew :bootstrap:bootTestRun` |
| Run the app against your PostgreSQL (`JOFI_DB_URL`, `JOFI_DB_USERNAME`, `JOFI_DB_PASSWORD`) | `./gradlew :bootstrap:bootRun` |
| Regenerate jOOQ code from the migrations | `./gradlew :adapters:persistence:generateJooq` |
| Regenerate the API contract `../api/openapi.json` after a controller/DTO change (commit it) | `./gradlew :adapters:web:updateOpenApiSpec` |
| Outdated dependencies report (not part of `check`) | `./gradlew dependencyUpdates` |
| Refresh lockfiles after a dependency change | `./gradlew resolveAndLockAll :build-logic:resolveAndLockAll --write-locks` |
| Refresh checksums after a dependency change | `./gradlew --write-verification-metadata sha256 resolveAndLockAll :build-logic:resolveAndLockAll --write-locks check help --no-build-cache --rerun-tasks --no-configuration-cache` |

A JDK 25 toolchain is required; if only a JRE is installed, Gradle provisions a JDK via the
foojay resolver. Configuration cache, build cache and parallel execution are on.
The build (jOOQ codegen) and the integration tests start PostgreSQL via Testcontainers, so Docker
or Podman (Docker-compatible socket, `DOCKER_HOST`) must be available. The image is pinned by
digest in `gradle.properties` (`jofi.postgresImage`).

## Modules and what may depend on what

```
domain  <-  application  <-  adapters/*  <-  bootstrap
                                            architecture-tests (tests only, sees all)
```

- `domain`: Kotlin stdlib only. Entities, value objects, domain services, domain events.
- `application`: use cases and ports; depends on `domain` only. No frameworks.
- `adapters/<kind>`: framework code (web, persistence, net, crypto, jobs, ai, backup, ...); depends on `application`.
  Adapters never depend on each other (two exemptions: persistence adapters of every context use
  the generated jOOQ code in `shared.adapter.persistence.jooq`, ADR-0032; web adapters use the shared
  web conventions in `shared.adapter.web`, ADR-0041).
- `bootstrap`: the Spring Boot app. Wires use cases as beans, holds config and framework-bound
  adapters that belong nowhere else (e.g. build info).
- `architecture-tests`: ArchUnit, Konsist and Spring Modulith rules over all production code.

Gradle enforces the module direction (a wrong import does not compile). Tests enforce the rest.

## Package convention

`io.github.scriptibus.jofi.<context>.<layer>` with layer one of:

- `domain` (in `domain/`)
- `application` for use cases, `application.port` for port interfaces (in `application/`)
- `adapter.<kind>`, e.g. `adapter.web` (in `adapters/<kind>/`, or `bootstrap/` for framework-bound glue)
- `config` for Spring wiring (in `bootstrap/`)

Contexts: `applications`, `companies`, `knowledge`, `documents`, `scanners`, `chat`, `training`,
`tasks`, `setup`, plus the `shared` kernel. Today `system` (proves the wiring), `setup` (AI
providers, per-task models, capabilities, costs, budget), `companies` (companies with their use cases, #88; contacts with
theirs, #89), `applications` (contracts: the application aggregate and its status pipeline, ADR-0044) and
`shared` exist.
The only class allowed directly in the base package is the application class; the only class
allowed directly in a context package is its Spring Modulith `ModuleMetadata`.

A context spans Gradle modules (e.g. `system.domain` lives in `domain/`, `system.adapter.web` in
`adapters/web/`). Spring Modulith sees each context as one application module; its sub-packages
are internal, so other contexts may not reach into them. `shared` is the exception: an **open**
module (`bootstrap/.../shared/ModuleMetadata.kt`, ADR-0032) whose domain types and ports every
context may use. Cross-context APIs of other contexts are exposed deliberately through Modulith
named interfaces: `companies.application.port.spi` (named interface `spi`, ADR-0041) holds the ports
the applications context implements for companies. Otherwise a context refers to another context's
aggregates by id only, with its own reference type (`applications.domain.CompanyRef`, ADR-0041).

## Shared kernel ports (ADR-0032)

Use these instead of reaching for a framework; each returns a sealed result and never throws.

| Port | For | Adapter |
|---|---|---|
| `ChangelogPort` | the audit trail of every mutation | `adapters/persistence` |
| `LlmPort`, `EmbeddingPort` | AI calls; every request carries its `AiTask`; text from stored items goes in as `ContentPart.Sourced` | `AiGatewayAdapter` (`setup.adapter.ai`, module `adapters/ai`, ADR-0043) |
| `AiVisibilityPort` | the "never send to AI" flags: a verdict per content source plus all flagged values; unknown source = refused | `NoKnowledgeYetAiVisibilityAdapter` (`bootstrap`) until the knowledge context (M2) |
| `OutboundHttpPort` | every outbound HTTP fetch (SSRF guard, threat model T1) | `adapters/net` (ADR-0034) |
| `JobSchedulerPort` | background jobs (ids-only arguments), recurring schedules with a random delay | `JobRunrJobSchedulerAdapter` (`adapters/jobs`, ADR-0038) |
| `JobHandlerPort` | inbound: runs the jobs of one type in the worker; one `*JobAdapter` per type | `<context>.adapter.jobs` |
| `JobLogPort` | the job log the user sees (status, attempts, failure reason code) | `JobRunrJobLogAdapter` (`adapters/jobs`) |
| `SecretStorePort` | encrypted secrets such as API keys; owners keep a `SecretId` | `SecretRepository` (`adapters/persistence`) over `SecretCipherPort` (Tink, `adapters/crypto`), ADR-0035 |
| `SecretCipherPort` | AES-GCM under the master keyset; **only secret stores use it** | `adapters/crypto` |
| `TransactionPort` | one transaction around a mutation and its changelog entry; commit only accepted results | `adapters/persistence` |
| `ConfirmationStorePort` | pending two-step confirmations; features call `ConfirmActionUseCase`, never the port | `InMemoryConfirmationStoreAdapter` (`bootstrap`), ADR-0039 |
| `DomainEventPort` | domain events (`shared.domain.DomainEvent`, ids and states, no third-party personal data) to other contexts; publish inside the mutation's transaction, false = roll back | `SpringDomainEventAdapter` (`bootstrap`), ADR-0041 |

Kernel types never depend on a context. `AiTask` lives in `shared.domain.ai` for that reason; the
`setup` context owns what it configures around it.

AI calls: callers use only `LlmPort`/`EmbeddingPort`. The gateway behind them resolves the task's
model once per call, checks capabilities, applies the budget and the "never send to AI" filter,
meters the cost, and calls `AiProviderPort` (`setup.application.port`), which Spring AI implements
in `setup.adapter.ai` (#19, ADR-0040, ADR-0043). Only the gateway implements `LlmPort`/`EmbeddingPort`
and calls `AiProviderPort`; nothing outside `setup.adapter.ai` may use `AiProviderPort` (architecture
tests). The setup use cases (#23: providers, keys, model refresh, capability corrections, task
assignments; #24: the monthly budget cap) accept only `Actor.User`, since the provider config decides where prompts go and feeds
the AI transport's allowlist; never expose them as MCP or AI tools. `SetupRules` (architecture tests)
enforces it: only `setup.adapter.web` (and `setup.config`) may depend on the mutating setup use cases and
inbound ports, and only `..adapter.web..` may name `Actor.User` (reviewed allowlist in `SetupRules`). Mark text copied from a stored
item as `ContentPart.Sourced`, and handle the results `PrivacyFilterFailed` and `Withheld`. `ModelCatalogPort` lists a provider's models with their known
capabilities for the setup checks. Costs and the budget are in USD only; an unknown cost is null.

## Rules (all fail `check`)

- detekt (`config/detekt/detekt.yml`, on top of defaults): functions <= 30 lines, cyclomatic
  complexity <= 10, <= 5 function / 7 constructor parameters (data classes exempt), class <= 300
  lines, nesting depth <= 3, no `!!`, no wildcard imports, no magic numbers (tests exempt), no
  `lateinit` in domain, no catching `Exception`/`Throwable` outside adapters, no Spring/jOOQ/JPA
  imports in domain/application.
- ktlint via Spotless, and the SPDX header on every `.kt`/`.kts` file:
  `// SPDX-FileCopyrightText: 2026 Jofi contributors` / `// SPDX-License-Identifier: AGPL-3.0-or-later`.
- Kotlin compiler warnings are errors (deprecated APIs therefore break the build).
- Architecture tests: package convention; domain/application free of Spring, jOOQ, JPA, Jackson;
  layers only point inwards; adapters independent; no cycles between contexts; classes in
  `application` end in `UseCase`, have exactly one public method; ports are interfaces named
  `*Port`; port implementations end in `Adapter`/`Repository`; `@RestController`s end in
  `Controller`, receive only use cases and never touch ports/adapters/repositories; every inbound
  port has exactly its use case as implementor (`InboundPortRules`); only
  the `adapters/net` module uses HTTP clients, sockets or `java.net.URL` (ADR-0034; the AI adapter
  may use a named list of vendor SDK types, ADR-0040); only
  `shared.adapter.jobs` uses JobRunr, and nothing its lambda/annotation jobs (ADR-0038); domain data
  and value classes only have `val`s; no `lateinit` in domain; Spring Modulith `verify()`.
- Coverage (Kover): `domain` and `application` >= 70 % lines.
- Licenses (licensee): only MIT, Apache-2.0, BSD-2/3, ISC, MPL-2.0, LGPL-2.1/3.0, EPL-2.0,
  GPL-3.0, AGPL-3.0. `allowUrl` entries need a `because` naming the real license.
- Supply chain: dependency locking (STRICT) and SHA-256 dependency verification. A new or bumped
  dependency needs refreshed lockfiles and `gradle/verification-metadata.xml` (commands above).

## How to add things

- **A use case**: `application/.../<context>/application/<Verb><Noun>UseCase.kt`, a plain class
  taking ports in its constructor with one public method `execute(...)`. Return domain types or
  sealed result types; do not throw across ports. Unit test it with Kotest assertions + MockK.
  Register it as a `@Bean` in `bootstrap/.../<context>/config/<Context>Configuration.kt`.
- **A port**: interface `<Name>Port` in `<context>.application.port`.
- **An adapter**: implement the port in `adapters/<kind>/.../<context>/adapter/<kind>/<Name>Adapter.kt`
  (or `...Repository`). New adapter kinds get a new Gradle module under `adapters/` using
  `id("jofi.spring-conventions")`, added to `settings.gradle.kts` and to `bootstrap` and
  `architecture-tests` dependencies.
- **A background job**: see `adapters/jobs/AGENTS.md` (job type in the domain, a use case, a
  `*JobAdapter` implementing `JobHandlerPort` that returns `JobOutcome`; ids-only arguments; reason codes,
  never messages). `app` enqueues through `JobSchedulerPort`; only the `worker` profile runs jobs.
- **An outbound HTTP call**: inject `OutboundHttpPort` (fetches of user/posting/page URLs). The AI
  adapter's SDK clients get the guarded SDK transports (`OpenAiSdkHttpClient`,
  `AnthropicSdkHttpClient`) instead (ADR-0040). Never create an HTTP client elsewhere; see
  `adapters/net/AGENTS.md` and `adapters/ai/AGENTS.md`.
- **A table or migration**: see `adapters/persistence/AGENTS.md` (timestamp versions, one open
  migration PR at a time, jOOQ codegen, export/import coverage, changelog on every mutation). Every
  table goes into `BackupTables.EXPORTED` (and gets a seed row in `DatabaseBackupRepositoryTest`) or
  into `BackupTables.EXCLUDED` with a reason; `DatabaseBackupRepositoryTest` fails otherwise (ADR-0042).
- **Files in the data volume**: only below `<data>/knowledge` or `<data>/documents`, which backups carry
  (`adapters/backup`, ADR-0042); anything else needs its own entry in the backup format.
- **A mutation**: append a `ChangelogEntry` with the acting `Actor` through `ChangelogPort` in the
  same use case (spec §13), inside `TransactionPort.inTransaction` so both are stored or neither.
  The changelog is append-only; the audit lens checks the actor.
- **A delete or outward-facing action** (spec §9, ADR-0039), enforced by `ConfirmationRulesTest`:
  - The feature use case takes `ConfirmActionUseCase` and a `ConfirmationRequester` + optional
    `ConfirmationToken`. Inside one `TransactionPort.inTransaction` it reads the targets, builds the
    `ConfirmableAction` from **that same read** (operation `<context>.<verb>`; targets = concrete,
    server-resolved ids, never filters; a `ConfirmationEffect(kind, name, counts)`), calls the gate,
    and mutates only with the `ConfirmationResult.Confirmed` it returns. It returns
    `ConfirmationResult.Unconfirmed` as one case of its sealed result.
  - The port method takes the proof: `fun delete(id: ThingId, proof: ConfirmationResult.Confirmed)`,
    and the adapter checks `proof.covers("<context>.delete", id.toString())` first. Only the gate can
    mint a `Confirmed` (internal constructor + architecture test).
  - Port methods named `delete*`/`remove*`/`send*`/`purge*` may only be called by use cases that hold
    the gate or pass a `Confirmed`; `DELETE` endpoints (and those in
    `ConfirmationRules.OUTWARD_FACING_ENDPOINTS`, which every new outward endpoint joins) take the
    `Jofi-Confirmation` header. Allowlist entries need a reason and a human review.
  - Never put the gate in a controller or MCP tool: it must hold for every caller. Test the
    unconfirmed, confirmed, replay and changed-effect paths.
- **A controller**: `adapters/web/.../<context>/adapter/web/<Name>Controller.kt`; inject use cases
  only, map domain types to DTOs (`*Response`/`*Request`) in the same package. Test with a
  `@WebMvcTest` slice (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `MockMvcTester`).
  Then regenerate and commit the API contract (see "API contract" below).
- **A context**: create `<context>` packages in the modules you need, following the convention.
- **A context contract** (ADR-0041, `companies` is the example): domain model with a raw `*Input` whose
  `validate()` normalizes (NFC, trim) and returns violations as values, a `version` for optimistic
  locking, the changelog entity type on the id (`ENTITY_TYPE`, `toEntityRef()`), a sealed store result
  and a sealed use-case result with a `Failure` branch; a repository port plus one inbound
  `<Verb><Noun>Port` per use case in `<context>.application.port.inbound` (listed in
  `InboundPortRules.AWAITING_USE_CASE` until `<Verb><Noun>UseCase` implements it); the migration with named
  constraints never stricter than the domain and a schema test at every limit; a controller with typed
  DTOs and `@ProblemResponses`, answering 501, plus a `<Context>Problems` mapping.
- **A dependency or plugin**: look up the latest stable version at the official source, read its
  current docs, add it to `gradle/libs.versions.toml`, then refresh locks and checksums. List
  version + doc link in the PR (spec 4.10).

## API contract (ADR-0016, ADR-0033)

- `api/openapi.json` at the repository root is the contract the frontend client is generated from.
  `OpenApiSpecTest` (in `adapters/web`, part of `check`) renders it from all controllers with
  springdoc-openapi and fails when the committed file differs. Fix: run
  `./gradlew :adapters:web:updateOpenApiSpec`, review the diff and commit it. Never edit it by hand.
- springdoc is on the test classpath only; the running app does not serve the spec.
- Controller method names become operation ids and frontend hook names (`getSystemInfo` ->
  `useGetSystemInfo`): make them unique and descriptive (`<verb><Noun>`).
- Non-null Kotlin DTO properties are `required` in the contract; nullable ones are optional.
- **Errors are RFC 9457 problem details**, the one error schema (`ProblemDetail`, declared as the
  `default` response of every operation). `spring.mvc.problemdetails.enabled` turns framework
  errors into `application/problem+json`; `shared.adapter.web.UnexpectedErrorAdvice` (lowest
  precedence) answers everything else: an `ErrorResponse` keeps its problem, an exception annotated
  with `@ResponseStatus` keeps that status, any other exception is a 500 without internal details.
  The advice rethrows Spring Security's access/authentication exceptions; the security filter chain
  answers them (and every request without a session) with 401/403 problem details (`SecurityProblemHandler`).
  The use case returns a sealed result; the controller maps its failure cases to a `ProblemDetail`
  and hands it to Spring as an `ErrorResponse`, so the success return type (and its schema in the contract) stays typed:
  `is NotFound -> throw ErrorResponseException(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "..."), null)`.
  Set a `type` URI `urn:jofi:problem:<context>:<code>` when clients must tell cases apart. The
  exception never leaves the web adapter (ports still return sealed results). No internal details
  (stack traces, SQL, personal data) in `detail`.
- Breaking changes (removed paths or fields, new required inputs, narrowed types) fail the
  `api-breaking-changes` CI job (oasdiff against the base branch). Prefer additive changes. The
  only override is an entry in `.github/oasdiff/breaking-changes-allowed.txt` **on main**: the job
  reads the file from the PR's base branch, so Lucas lands the approval first and the breaking PR
  then deletes the entry (CI fails a contract change that leaves entries behind). Agents never add
  an entry; they explain the break in the PR and ask.

## Testing expectations

JUnit 6 (Jupiter) + Kotest assertions + MockK. Domain and application: plain unit tests, no
Spring. Adapters: slice tests; persistence against a real PostgreSQL via Testcontainers (Flyway
from zero, jOOQ round-trips), never an in-memory database. Bootstrap: `@SpringBootTest` smoke test
(context + `/actuator/health`) with `PostgresTestConfiguration` as the service connection.
Keep `./gradlew check` green before you finish.

Full-stack e2e (`cd frontend && pnpm e2e`, ADR-0036) runs the real image against a wire-level fake AI
provider, seeded as an `OPENAI_COMPATIBLE` provider with model `fake-<task>` per task. The backend has
**no e2e code, profile or flag**: seed through the public API (or SQL in `frontend/tests/stack/seed/db`
for data without an API yet). `scripts/e2e-isolation-test.sh` fails if e2e markers reach the image.

## Container image

The repository-root `Dockerfile` builds the frontend, then `:bootstrap:bootJar` (the SPA goes into
`classpath:/static/`), and creates the JDK AOT cache (ADR-0003) in a training run that starts the
Spring context and exits on refresh (`-Dspring.context.exit=onRefresh`). That run has **no database
or network**: startup code must not need them (Flyway is switched off there, `JOFI_DB_URL` gets a
placeholder, and so does `JOFI_DB_PASSWORD`, without which startup fails). If you add a bean that
connects at startup, make it skip the training run too, or the image build fails.
The image build has **no Docker daemon**, so build-time tasks cannot use Testcontainers. jOOQ codegen
therefore uses an external database when `JOFI_CODEGEN_JDBC_URL` (+ `_USER`, `_PASSWORD`) is set:
the backend build stage is the pinned pgvector image with the JDK copied in, and its single Gradle
`RUN` starts that PostgreSQL on loopback, builds, and stops it. Keep the image digest there in sync
with `jofi.postgresImage`. Build and smoke-test the stack from the repository root with
`cp .env.example .env && scripts/compose-smoke-test.sh` (heavy: one build at a time).

## Documented exceptions

- **detekt 2.0.0-alpha.6** (pre-release, approved by Lucas): detekt 1.23.x only supports Kotlin
  <= 2.0. detekt runs in its own `detekt` configuration with its own Kotlin 2.4.10 compiler, so it
  is isolated from the project's Kotlin 2.4.20. Its Gradle plugin still calls the deprecated
  `Configuration.setVisible` (Gradle deprecation warning, not our code). Move to 2.0.0 stable
  once released.
- **Kotlin 2.4.20 vs Spring Boot 4.1.1**: Boot manages Kotlin 2.3.21 (requires >= 2.2); the Kotlin
  Gradle plugin's 2.4.20 wins. `-Xannotation-default-target=param-property` (recommended by Boot)
  is the default in Kotlin 2.4 and passing it is a compiler error there, so it is not set.
- **Konsist 0.17.3** is compiled against `kotlin-compiler-embeddable` 2.0.21; on the test
  classpath the Boot BOM aligns it to 2.3.21, which works for source parsing.
- **jOOQ 3.21.8 and Flyway 13.7.0 over the Boot BOM** (which manages 3.21.7 and 12.4.0): the
  explicit catalog versions win in Gradle. The newest releases younger than 7 days were skipped
  (same minimum release age as Renovate). Flyway 13 works with Boot 4.1's autoconfiguration
  (bootstrap tests migrate at startup); drop the override if Boot starts managing a newer one.
- **licensee `allowUrl("https://opensource.org/license/mit")`**: SLF4J (`slf4j-api`,
  `jul-to-slf4j`, via Spring Boot logging) declares MIT by URL instead of an SPDX id.
- **licensee `allowUrl` for Flyway, jOOQ and the PostgreSQL driver**: their poms name Apache-2.0
  (Flyway, jOOQ Open Source Edition) and BSD-2-Clause (pgjdbc) with a URL licensee cannot map.
- **Jackson 2 on the `adapters/web` test classpath**: springdoc 3 (via swagger-core 2.2) still reads
  models with Jackson 2 and needs `com.fasterxml.jackson.module:jackson-module-kotlin` to honour
  Kotlin nullability. Test-only; the app uses Jackson 3.
- **licensee `allowUrl("https://www.bouncycastle.org/licence.html")`**: Bouncy Castle (`bcprov-jdk18on`,
  argon2id for Spring Security's encoder) names its licence by URL; the text is the MIT license.
- **licensee `allowDependency` for Spring Session 4.1.1** (`spring-session-core`, `-jdbc`): the poms name
  a "Broadcom Foundation License" by a release-tooling bug (spring-session#3910); the jars and the
  repository are Apache-2.0. Pinned to 4.1.1 so the next version is checked again.
- **`sun.misc.Unsafe` warning from protobuf** (via Tink): JDK 25 prints a one-time "terminally
  deprecated method" warning when protobuf first runs. It is a runtime notice from a dependency,
  not a deprecated API we call; it goes away when protobuf stops using `Unsafe`.
- **`org.jobrunr:jobrunr-bom` excluded** from every JobRunr dependency (ADR-0038): its Gradle metadata
  would raise Jackson, logback, HikariCP, pgjdbc and more above the Spring Boot BOM. Keep the exclusion on
  each JobRunr dependency when adding one.
- **licensee `allowDependency` for `org.reactivestreams:reactive-streams:1.0.4`** (via jOOQ ->
  `r2dbc-spi`): MIT-0, which the frontend already accepts as strictly more permissive than MIT.
- **Spring AI 2.0.1 with the vendor SDK cores at Spring AI's versions** (`openai-java-core` 4.49.0,
  `anthropic-java-core` 2.52.0; newer ones exist): the Spring AI BOM does not manage them and its
  model classes are compiled against these (ADR-0040). They bring **Jackson 2** onto the runtime
  classpath, with the catalog's security override. OkHttp is excluded from the Spring AI modules.
- **licensee `allowDependency` for ANTLR** (`antlr4-runtime` 4.13.1, `ST4` 4.3.4, `antlr-runtime`
  3.5.3, via Spring AI's prompt templates): BSD-3-Clause, declared only by URL. Pinned to these
  versions so a new release is checked again.
