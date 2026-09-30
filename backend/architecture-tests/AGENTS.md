<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# architecture-tests

Owns the executable architecture rules. Test sources only; depends on every production module.

- `LayerDependencyTest` (ArchUnit, compiled classes): package convention, framework-free core,
  inward-only layers, independent adapters, no cycles between contexts.
- `AdapterRulesTest` (ArchUnit): controller/adapter naming, controllers only use use cases,
  ports are interfaces, only `setup.adapter.ai` uses `AiProviderPort` (ADR-0032), only
  the `adapters/net` module uses HTTP clients, sockets or `java.net.URL` (ADR-0034, with
  known-bad fixtures incl. an impostor class in the net package from another module).
- JobRunr (ADR-0038): only `shared.adapter.jobs` and `shared.config` may use `org.jobrunr` types, and no
  class may use JobRunr's lambda/annotation job APIs (`JobScheduler`, `BackgroundJob`, `@Job`, `@Recurring`,
  `@AsyncJob`), with known-bad fixtures in `fixture.adapter.web` and `shared.adapter.jobs` (test sources).
- `AdapterRules`: rules shared with `AdapterRulesFixtureTest`, which evaluates them against
  known-bad and known-good fixture classes in `io.github.scriptibus.jofi.fixture` (test sources,
  never part of the production import). Adapter independence has one narrow exemption:
  `..adapter.persistence..` may use the generated jOOQ code in `shared.adapter.persistence.jooq`
  (ADR-0032); a web adapter doing the same is still rejected.
- `ConfirmationRulesTest` (ArchUnit, ADR-0039, rules in `ConfirmationRules`): `DELETE` and listed
  outward-facing handlers take the `Jofi-Confirmation` header; use cases calling `delete*`/`remove*`/
  `send*`/`purge*` port methods hold `ConfirmActionUseCase` or pass a `Confirmed`; only the gate mints
  confirmations. Known-bad fixtures in `fixture.adapter.web` and `fixture.application` must be rejected;
  the allowlists (`ENDPOINTS_WITHOUT_CONFIRMATION`, `USE_CASES_WITHOUT_CONFIRMATION`) need a reason each.
- `SourceConventionsTest` (Konsist, sources): use case naming + one public method, `*Port`
  naming, controller constructor takes use cases only, immutable domain types, no `lateinit`.
- `ModulithTest` (Spring Modulith): contexts are application modules, `verify()` passes, only
  `shared` is open and exposes its domain types and ports, a second context (`setup`) depends on
  it, other contexts keep their sub-packages internal.

Rules:
- Never weaken or delete a rule to make a change pass; fix the code. Changing a rule needs a
  human review (spec 4.4).
- A new rule must fail on a known-bad example before it lands (try it, then revert).
