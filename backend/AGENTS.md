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
| Run the app (http://localhost:8080/api/system/info) | `./gradlew :bootstrap:bootRun` |
| Outdated dependencies report (not part of `check`) | `./gradlew dependencyUpdates` |
| Refresh lockfiles after a dependency change | `./gradlew resolveAndLockAll :build-logic:resolveAndLockAll --write-locks` |
| Refresh checksums after a dependency change | `./gradlew --write-verification-metadata sha256 resolveAndLockAll :build-logic:resolveAndLockAll --write-locks check help --no-build-cache --rerun-tasks --no-configuration-cache` |

A JDK 25 toolchain is required; if only a JRE is installed, Gradle provisions a JDK via the
foojay resolver. Configuration cache, build cache and parallel execution are on.

## Modules and what may depend on what

```
domain  <-  application  <-  adapters/*  <-  bootstrap
                                            architecture-tests (tests only, sees all)
```

- `domain`: Kotlin stdlib only. Entities, value objects, domain services, domain events.
- `application`: use cases and ports; depends on `domain` only. No frameworks.
- `adapters/<kind>`: framework code (web, later persistence, ai, net, ...); depends on `application`.
  Adapters never depend on each other.
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
`tasks`, `setup`, plus the `shared` kernel. Today only `system` exists (proves the wiring).
The only class allowed directly in the base package is the application class.

A context spans Gradle modules (e.g. `system.domain` lives in `domain/`, `system.adapter.web` in
`adapters/web/`). Spring Modulith sees each context as one application module; its sub-packages
are internal, so other contexts may not reach into them. Cross-context APIs will be exposed
deliberately (Modulith named interfaces or the `shared` kernel) when the first one is needed.

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
  `Controller`, receive only use cases and never touch ports/adapters/repositories; domain data
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
- **A controller**: `adapters/web/.../<context>/adapter/web/<Name>Controller.kt`; inject use cases
  only, map domain types to DTOs (`*Response`/`*Request`) in the same package. Test with a
  `@WebMvcTest` slice (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `MockMvcTester`).
- **A context**: create `<context>` packages in the modules you need, following the convention.
- **A dependency or plugin**: look up the latest stable version at the official source, read its
  current docs, add it to `gradle/libs.versions.toml`, then refresh locks and checksums. List
  version + doc link in the PR (spec 4.10).

## Testing expectations

JUnit 6 (Jupiter) + Kotest assertions + MockK. Domain and application: plain unit tests, no
Spring. Adapters: slice tests. Bootstrap: `@SpringBootTest` smoke test (context + `/actuator/health`).
Keep `./gradlew check` green before you finish.

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
- **licensee `allowUrl("https://opensource.org/license/mit")`**: SLF4J (`slf4j-api`,
  `jul-to-slf4j`, via Spring Boot logging) declares MIT by URL instead of an SPDX id.
