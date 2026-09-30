<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0041: Context contracts: inbound ports, validation as values, optimistic versions

- Status: accepted
- Date: 2026-09-30
- Source: issue #73 (M1-C1a, the first M1 contract); AGENTS.md §1 "contracts first", §3; refines ADR-0005,
  ADR-0033, ADR-0039

## Context

Every M1 context starts with a contract PR (domain model, ports, migration, API shape) so feature PRs
can build on it in parallel. The companies contract is the first; the others (contacts, applications,
tasks, ...) copy its shape. Three questions had no answer yet:

- How does a contract fix the signatures of use cases that do not exist yet? Controllers may only
  receive use cases, and ports may only be interfaces.
- User, AI and external-client input arrives in controllers and MCP tools. Domain constructors throw on
  broken invariants, and an exception there would end as a 500 (`UnexpectedErrorAdvice`), not a 400.
- The user, the built-in AI and MCP clients can edit the same entity at the same time. Adding a
  required version to requests later would be a breaking API change.

## Decision

- **Inbound ports.** A contract declares one interface per use case in `<context>.application.port`,
  named `<Verb><Noun>Port` with the single method `execute(...)`, returning the context's sealed result
  (e.g. `CreateCompanyPort`). The feature PR implements it as `<Verb><Noun>UseCase`; controllers and MCP
  tools inject the use case class (architecture rule), so the port is the contract both sides code
  against. Mutating ports take the acting `Actor` explicitly (changelog, spec §13); deletes take the
  `ConfirmationRequester` and token (ADR-0039), and the repository's delete takes the `Confirmed` proof.
- **Validation as values.** Untrusted input enters the domain as a raw `*Input` (strings as sent) whose
  `validate()` normalizes it (trim, blank optional text is absent) and returns `Valid(value)` or
  `Invalid(violations)`, every violation a field enum plus a problem kind (`REQUIRED`, `TOO_LONG`,
  `TOO_MANY`, `INVALID_URL`). Controllers map `Invalid` to `400` problem details listing the violations;
  domain constructors keep `require` only as a guard against programming errors. Limits are public
  constants on the domain type and mirrored by check constraints in the migration.
- **Optimistic versions.** Every editable aggregate has a `version` (starting at 0, +1 per change). The
  response carries it; every change request carries `basedOnVersion`. The use case answers
  `VersionConflict` (409) when it does not match, and the repository's `update` stores only if the stored
  version is exactly one below the new one, so a race between read and write is caught as well.
- **API shape before the use cases.** The contract's controller declares every endpoint with typed
  request and response DTOs and answers `501 Not Implemented` problem details; the feature PR replaces
  the bodies. Enums in the API are copies of the domain enums (`CompanySizeBand` for `CompanySize`),
  mapped by name and tested for completeness.
- **Changelog entity types** are registered as `<Aggregate>Id.ENTITY_TYPE` (e.g. `company`) with
  `toEntityRef()`, and listed in `backend/adapters/persistence/AGENTS.md`. They are stored in every
  entry, so they are never renamed.
- **Repositories come with the use cases**, not with the contract (as in `setup`, #23): the contract
  proves the migration's constraints with a schema test instead.

## Consequences

- Each use case costs one extra small interface, but feature PRs, MCP tools and the UI can start from
  the contract without waiting for each other.
- Clients always read before they write and must handle `409` by reloading.
- A contract PR changes no behaviour: until its feature PR lands, its endpoints answer 501.
