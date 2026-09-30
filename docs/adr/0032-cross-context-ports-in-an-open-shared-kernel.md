<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0032: Cross-context ports live in an open `shared` kernel

- Status: accepted
- Date: 2026-09-30
- Source: issue #11 (M0-C2), its carry-over from #10 and the security review of PR #59; refines ADR-0005,
  ADR-0010, ADR-0011, ADR-0017

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

### Open shared kernel

- `shared` is a Spring Modulith `OPEN` application module, declared by
  `bootstrap/.../shared/ModuleMetadata.kt` (`@PackageInfo` + `@ApplicationModule(type = OPEN)`, the
  Kotlin form of `package-info.java`, from `spring-modulith-api` 2.1.1). Keeping the annotation in
  `bootstrap` keeps `domain` and `application` framework-free; the package-convention test allows
  exactly `<context>.ModuleMetadata` in a context root. Every other context stays closed.
  Docs: https://docs.spring.io/spring-modulith/reference/fundamentals.html
- Infrastructure ports with no owning context go to the kernel: `LlmPort`, `EmbeddingPort`,
  `OutboundHttpPort`, `JobSchedulerPort`, `SecretStorePort`, next to `ChangelogPort`, in
  `shared.application.port`. Their value and sealed result types live in `shared.domain.<ai|http|job|secret>`
  (the port package only holds interfaces).
- `AiTask` lives in `shared.domain.ai`, not in `setup.domain`: every AI request carries its task, so the
  kernel must know the type; placing it in `setup` would make `shared` depend on `setup` and form a
  cycle. `setup` owns what it configures: providers, assignments, capabilities, costs, budget, and their
  repository ports in `setup.application.port`.
- Ports owned by one context stay in that context. A context exposes a port to others later through a
  Modulith named interface, not by moving it to `shared`. Kernel types never depend on a context.

### AI port contract

- Callers in every context depend only on the task-based `LlmPort` and `EmbeddingPort`.
- Both are implemented by one **AI gateway**: a decorator named `*Adapter` in package `setup.adapter.ai`,
  inside the Gradle module `adapters/ai` (#20), so it stays under the protected path and CODEOWNERS. Per
  call it resolves the task's assignment **once** (through `setup`'s ports) into a `ResolvedModel`
  (provider config + model name), runs the capability check, the "never send to AI" filter and the
  budget decision, calls the provider port with that target, and meters the result as a `CostEntry`.
- **`AiProviderPort`** (`setup.application.port`) is the provider-facing port: `complete`, `stream` and
  `embed` for a given `ResolvedModel`. Spring AI implements it in `setup.adapter.ai` (#19); it reads the
  API key through `SecretStorePort` and does no routing, filtering or metering.
- An architecture test fails if anything outside `setup.adapter.ai` depends on `AiProviderPort`, so no
  call can bypass the gateway.
- Streaming polls a cancellation callback between fragments and returns `AiResult.Cancelled`; an
  exception from the fragment consumer is caught by the adapter and also ends as `Cancelled`.

### Capabilities, costs and currency

- Capabilities belong to a **provider and model** (`ai_model_capability`, `ModelCapabilityProfile`,
  filled by #19, correctable by the user), not to a task assignment: several tasks share a model, and
  re-assigning a task must not lose what the model can do. An assignment names task, provider and model.
- **USD is the single accounting currency** for `ai_cost_entry` and `ai_monthly_budget`: all five
  supported providers price in US dollars, so costs are never converted. The UI may show conversions
  later. Enforced by a check constraint on both tables and domain invariants on `CostEntry` and
  `MonthlyBudget`; comparing spending in another currency returns `CurrencyMismatch` instead of throwing.
- Cost entries snapshot the provider kind (no foreign key, the history outlives a deleted provider) and
  are append-only through a row trigger, like the changelog.

### Persistence adapters and generated jOOQ code

- jOOQ code is generated for the whole schema into `shared.adapter.persistence.jooq` (ADR-0030). The
  "adapters do not depend on other adapters" rule has one narrow exemption: classes in
  `..adapter.persistence..` may depend on that package. Any other adapter kind using it is still
  rejected; fixture tests prove both directions.

### Guardrails in the types

- Requests for tasks that read untrusted input reject tools (threat model T2).
- `LlmRequest`, the messages, tool calls, `LlmResponse`, `EmbeddingRequest`, `SecretValue` and
  `ResponseBody` print roles and sizes only; `OutboundRequest` prints method, host and header names,
  never header values or the path (T4). Header values with CR, LF or NUL are rejected.
- A provider config holds a secret id, never the key (ADR-0017); its base URL may not carry user info,
  a query or a fragment (domain and database), and one secret belongs to at most one provider.
- Outbound requests accept any absolute URI, so the SSRF guard answers `Blocked` instead of throwing (T1).

## Consequences

- Any context may use any public type of `shared`, including its adapters from Modulith's point of
  view. The ArchUnit layer and adapter-independence rules still stop other contexts from reaching the
  kernel's adapters, apart from the generated jOOQ code for persistence adapters.
- `shared` must stay small and generic: new kernel types need a reason why no single context owns them.
- #19 implements `AiProviderPort` and fills `ai_model_capability`; #20 builds the gateway; neither may
  add a second path to a provider.
