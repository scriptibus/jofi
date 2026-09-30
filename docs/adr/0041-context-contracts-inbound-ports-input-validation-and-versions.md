<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0041: Context contracts: inbound ports, validation as values, optimistic versions

- Status: accepted
- Date: 2026-09-30
- Source: issue #73 (M1-C1a, the first M1 contract) and Lucas's review of PR #130; AGENTS.md §1
  "contracts first", §3; refines ADR-0005, ADR-0030, ADR-0033, ADR-0039

## Context

Every M1 context starts with a contract PR (domain model, ports, migration, API shape) so feature PRs
can build on it in parallel. The companies contract is the first; the others (contacts, applications,
tasks, ...) copy its shape. Several questions had no answer yet:

- How does a contract fix the signatures of use cases that do not exist yet? Controllers may only
  receive use cases, and ports may only be interfaces.
- User, AI and external-client input arrives in controllers and MCP tools. Domain constructors throw on
  broken invariants, and an exception there would end as a 500 (`UnexpectedErrorAdvice`), not a 400.
- The user, the built-in AI and MCP clients can edit the same entity at the same time. Adding a
  required version to requests later would be a breaking API change.
- The database must back the domain's rules without ever rejecting what the domain accepts.
- What happens to dependants (applications, contacts) when a company is deleted.

## Decision

### Ports

- **Inbound ports** live in `<context>.application.port.inbound`, one interface per use case named
  `<Verb><Noun>Port` with the single method `execute(...)`, returning the context's sealed result (e.g.
  `CreateCompanyPort`). The feature PR implements each as `<Verb><Noun>UseCase` in `<context>.application`;
  controllers and MCP tools inject the use case class. `InboundPortRules` (Konsist, with fixtures)
  enforces exactly one implementor, the matching use case, and no adapter implementing one; ports whose
  use case is still to come are listed in `AWAITING_USE_CASE` with their issue, and the feature PR removes
  its entries.
- Mutating ports take the acting `Actor` explicitly (changelog, spec §13); deletes take the
  `ConfirmationRequester` and token (ADR-0039), and the repository's delete takes the `Confirmed` proof.
- **Updates are full replacements** (PUT): a field left out is cleared. MCP and AI tools read, change what
  they mean to change and send everything back with the version they read.

### Input, versions and time

- **Validation as values.** Untrusted input enters the domain as a raw `*Input` whose `validate()`
  normalizes it (Unicode NFC, trim, blank optional text is absent) and returns `Valid(value)` or
  `Invalid(violations)`, each violation a field plus a problem kind (`REQUIRED`, `TOO_LONG`, `TOO_MANY`,
  `INVALID_URL`). Domain constructors keep `require` only against programming errors. Limits are public
  constants on the domain type.
- **Optimistic versions.** Every editable aggregate has a `version` (0, +1 per change). Responses carry
  it; change requests carry `basedOnVersion`. The use case checks it first (a stale version is
  `VersionConflict`, 409, even when the change would be a no-op); an unchanged edit keeps the version and
  writes no changelog entry. The repository's `update` stores only if the stored version is exactly one
  below the new one, which also catches a race between read and write.
- Use cases take timestamps as `clock.instant().truncatedTo(ChronoUnit.MICROS)`, the precision of
  `timestamptz`, so an entity read back equals the one stored.
- Web addresses are kept as entered; hosts may be internationalised or contain underscores (checked as
  `IDN.toASCII`), since company and ATS sites use both.

### Database

- **Constraints are never stricter than the domain**, and SQL has no locale-dependent logic (no
  `lower()` or collation-dependent comparisons in constraints; whitespace is ASCII `[ \t\n\r\f\v]`, not
  `\s`). Case-insensitive rules stay in the domain. Schema tests accept every value at exactly each limit
  and a sample of what the domain accepts (non-ASCII text, IDN hosts, `İstanbul`/`istanbul`).
- Every constraint is **named**; repositories map violations by constraint name, never by SQL state alone.
- A check function is never tightened with `CREATE OR REPLACE`; a stricter rule is a new function and
  constraint added `NOT VALID`, then validated.
- **Dependants of a company:** `application.company_id` is `ON DELETE RESTRICT` (`application_company_fk`),
  so a company with applications cannot be deleted (`HasApplications`, 409). Contacts are deleted with
  their company (`contact_company_fk`, `ON DELETE CASCADE`), and the delete confirmation's effect counts
  them (`ConfirmationEffect("company", name, {"contacts": n})`), so the user sees "and n contacts".

### API

- The contract's controller declares every endpoint with typed request and response DTOs and answers
  `501 Not Implemented` until the feature PR. Its error answers are part of the contract now:
  `@ProblemResponses` (`shared.adapter.web`, read by the contract renderer) declares 400 with the
  `ValidationProblem` schema (`violations: [{field, problem}]`), 404 and 409 per operation; problem
  types `urn:jofi:problem:<context>:<code>` tell cases with the same status apart (`version-conflict`,
  `has-applications`). A `<Context>Problems` object in the web adapter maps each failure case.
- Web adapters of every context may use `shared.adapter.web` (confirmations, problem details): a
  narrow exemption from adapter independence, proven by fixtures.
- API enums are copies of the domain enums (`CompanySizeBand` for `CompanySize`), mapped by name and
  tested for completeness.
- **Changelog entity types** are `<Aggregate>Id.ENTITY_TYPE` (e.g. `company`) with `toEntityRef()`,
  listed in `backend/adapters/persistence/AGENTS.md`; they are stored in every entry, so never renamed.
- **Repositories come with the use cases**, not with the contract (as in `setup`, #23): the contract
  proves the migration's constraints with a schema test instead.

## Consequences

- Each use case costs one small interface, but feature PRs, MCP tools and the UI start from the
  contract without waiting for each other.
- Clients read before they write and handle `409` by reloading.
- A contract PR changes no behaviour: until its feature PR lands, its endpoints answer 501 (the search
  already answers 400 for bad paging).
- Markdown the API returns (research notes, the AI profile) is untrusted; the UI renders it sanitised
  (#108).
