<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0032: Cross-context ports live in an open `shared` kernel

- Status: accepted
- Date: 2026-09-30
- Source: issue #11 (M0-C2) and its carry-over from #10; refines ADR-0005, ADR-0010, ADR-0011, ADR-0017

## Context

Every context will call AI models, fetch URLs, enqueue background jobs and read secrets, and every
mutation appends to the changelog (`Actor`, `ChangelogPort`, ADR-0030). Spring Modulith treats each
direct sub-package of `io.github.scriptibus.jofi` as an application module whose sub-packages are
internal, so the first context that used `shared.domain` or `shared.application.port` failed
`verify()`. Kotlin has no `package-info.java`, and `domain`/`application` must stay free of Spring.

Two placements were possible for a port that several contexts use: the `shared` kernel, or the context
that owns the concept (e.g. AI ports in `setup`, which owns providers and model assignments).
ArchUnit forbids cycles between contexts, and `setup` itself depends on `shared` (changelog), so
nothing in `shared` may depend on `setup`.

## Decision

- **Open shared kernel.** `shared` is a Spring Modulith `OPEN` application module, declared by
  `bootstrap/.../shared/ModuleMetadata.kt` (`@PackageInfo` + `@ApplicationModule(type = OPEN)`, the
  Kotlin form of `package-info.java`, from `spring-modulith-api` 2.1.1). Keeping the annotation in
  `bootstrap` keeps `domain` and `application` framework-free; the package-convention test allows
  exactly `<context>.ModuleMetadata` in a context root. Every other context stays closed.
  Docs: https://docs.spring.io/spring-modulith/reference/fundamentals.html
- **Infrastructure ports with no owning context go to the kernel:** `LlmPort`, `EmbeddingPort`,
  `OutboundHttpPort`, `JobSchedulerPort`, `SecretStorePort`, next to `ChangelogPort`, in
  `shared.application.port`. Their value and sealed result types live in `shared.domain.<ai|http|job|secret>`
  (the port package only holds interfaces).
- **`AiTask` lives in `shared.domain.ai`, not in `setup.domain`.** Every AI request carries its task
  (routing, capability checks, metering and the privacy filter hook in without changing callers), so
  the kernel must know the type; placing it in `setup` would make `shared` depend on `setup` and form a
  cycle. `setup` owns what it configures: `ProviderConfig`, `ModelAssignment`, `Capability`,
  `CostEntry`, `MonthlyBudget` and their repository ports in `setup.application.port`.
- **Ports owned by one context stay in that context** (e.g. `setup`'s repository ports). A context
  exposes a port to others later through a Modulith named interface, not by moving it to `shared`.
- **Kernel ports never depend on a context.** Implementations that need a context's data live in that
  context's adapter package: the Spring AI adapter (#19) resolves provider and model through `setup`'s
  ports, so it belongs in `setup.adapter.ai`, not `shared.adapter.ai`.
- **Guardrails in the types:** requests for tasks that read untrusted input (pre-scoring,
  classification, language/tone detection, extraction, document generation) reject tools (threat
  model T2); `SecretValue` and `ResponseBody` never print their content (T4); a provider config holds a
  secret id, never the key (ADR-0017); outbound requests accept any absolute URI so the SSRF guard
  answers `Blocked` instead of throwing (T1).

## Consequences

- Any context may use any public type of `shared`, including its adapters from Modulith's point of
  view. The ArchUnit layer and adapter-independence rules still stop other contexts from reaching the
  kernel's adapters, so the kernel's reach stays limited to domain types and ports.
- `shared` must stay small and generic: new kernel types need a reason why no single context owns them.
- Open point: repositories of other contexts will need the generated jOOQ tables, which live in
  `shared.adapter.persistence.jooq`; the "adapters do not depend on other adapters" slice rule treats
  that as a cross-adapter dependency. The first feature PR with a non-`shared` repository must settle
  where generated code lives or how the rule treats it.
