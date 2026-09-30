<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0041: Context contracts: inbound ports, validation as values, optimistic versions

- Status: accepted
- Date: 2026-09-30
- Source: issue #73 (M1-C1a, the first M1 contract) and Lucas's review of PR #130, amended by #74
  (contacts), #76 (applications), #88 (company use cases) and #78 (sources and description snapshots); AGENTS.md §1
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
  `INVALID_URL`, and since #74 `INVALID_EMAIL`, `INVALID_PHONE`, `NOT_FOUND` for a referenced entity). Domain constructors keep `require` only against programming errors. Limits are public
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

### Personal data of third parties (contacts, #74)

Contacts are the first contract holding other people's personal data (spec §13). Such a contract:

- overrides `toString()` of every domain type and DTO that holds the data, so logs and exception
  messages never carry it; events (`ContactDeleted`) carry ids only;
- records changes in the changelog by field name, never by value (the changelog is append-only, and a
  deleted contact must leave nothing personal behind);
- deletes with confirmation (ADR-0039) and cascades to everything that only describes the person
  (`contact_channel`); tables in other contexts that refer to it react to `ContactDeleted`, and their
  foreign keys to `contact` never keep a dangling or blocking reference: link-table rows (e.g. application
  links) use `ON DELETE CASCADE`, an optional reference in an entity's own row (e.g. `task.contact_id`)
  uses `ON DELETE SET NULL`, and none uses `RESTRICT`/`NO ACTION`, which would block company deletes
  through `contact_company_fk`;
- validates contact data without over-restricting international formats: email addresses need an `@` with
  text on both sides (the address is kept as entered), phone numbers a digit (no E.164 normalisation,
  numbers are often noted without a country code), anything else is free text with a length limit.
  Text never contains U+0000, which PostgreSQL `text` cannot store (this applies to every context).

The company delete (#88) records one changelog entry per cascaded contact (ids only) besides its own.

The company delete (#88) reads the ids of the company's contacts (`CompanyRepositoryPort.findContactIds`, see below)
in its transaction, counts them in the confirmation effect and publishes `ContactDeleted` for each, since
the database cascade alone would not tell the other contexts.

### References across contexts (applications, #76)

The application aggregate is the first to refer to another context's aggregates (its company and
contacts). Spring Modulith keeps a context's sub-packages internal, and ArchUnit forbids cycles between
contexts, while both directions will be needed: the companies context shows application counts (#88) and
the applications context reacts to `ContactDeleted` (#90).

- A context refers to another context's aggregate **by id only, through a reference type of its own**
  (`applications.domain.CompanyRef`, `ContactRef`, each a `UUID`), never by importing the other context's
  id type. The database's foreign keys keep the reference valid, and the store result names a missing
  target (`CompanyNotFound`, `ContactNotFound`, mapped by constraint name). The contract needs no named
  interface and no dependency between contexts.
- **Dependencies run applications → companies only** (Lucas's decision in the review of PR #141). The
  companies context never depends on the applications context. What it needs from applications it
  declares as **outbound ports of its own** in `companies.application.port`, which the applications
  context implements (dependency inversion, so no cycle):
  - `ApplicationCountsPort` (#88): the number of applications per company, for `applicationCount`.
  - `LinkedApplicationsPort` (#89/#90): the ids of the applications linked to a contact, read in the
    contact delete's transaction **before** the delete, so it can write one changelog entry per affected
    application (ids only); the `application_contact_contact_fk` cascade then removes the links. No
    `ContactDeleted` listener is needed for link cleanup.

  Since #88 these ports live in **`companies.application.port.spi`**, the Spring Modulith named interface
  `spi` of `companies` (a `@PackageInfo @NamedInterface("spi") ModuleMetadata` in bootstrap, so
  `application` stays free of Spring; the architecture tests allow exactly that class there). The package
  is all that the applications context sees of companies, so its ports name companies by `UUID` and nest
  their result types (`ApplicationCountsPort.Counts`). `applications.adapter.persistence.ApplicationCountsRepository`
  implements `ApplicationCountsPort`. `LayerDependencyTest` (bytecode) and `SourceConventionsTest` (imports,
  which also sees value classes such as `CompanyRef`) fail any dependency of companies on applications.
- Text rules every context applies (NFC, trim, no U+0000, length) live in `shared.domain.text`; each
  context keeps its own violation enums, since the API names fields and problems per context.

Further rules the applications contract adds:

- **No write overwrites what it does not own** (lost updates): the repository stores details
  (`updateDetails`: detail columns, `version`, `updated_at`) and contact links (`replaceContacts`: rewrites
  the link rows only when the set differs) separately, each under the version check, and neither touches
  `unread` or the scores; `setUnread` touches only the flag.
- **Links to another aggregate are a set replaced as a whole** (`PUT /api/applications/{id}/contacts`
  with `basedOnVersion`), like any other update, so unlinking is not a `DELETE` and needs no confirmation.
- **Read/unread is not a change of the application**: it keeps `version` and `updatedAt`, so opening an
  application never conflicts with an edit. It still writes a changelog entry.
- **Money** is a decimal with exactly two places (`numeric(12,2)`), normalized on input, so what is read
  back equals what was stored; more places is `TOO_PRECISE`, never rounded. Amounts are gross.
  Currencies are ISO 4217 codes. Language tags are BCP 47, brought into canonical case on input
  (language lower-case, script title-case, region upper-case, with `Locale.ROOT`); the database accepts
  any case, so it stays at most as strict as the domain. `toString()` of pay types shows no amount and no estimate basis.
- API enums map to domain enums by name through one helper (`mapByName`), and a test compares their
  constants.

### The first feature on a contract (company use cases, #88)

- **The contacts a company delete cascades to** are read by `CompanyRepositoryPort.findContactIds`, not
  by `ContactRepositoryPort.findIdsByCompany` as first planned: the repository that runs the cascade
  reports it, the company delete needs no contact repository (#89 implements that one whole), and the
  delete use case stays within seven constructor parameters.
- **Domain events** go out through the kernel port `DomainEventPort` (`shared.domain.DomainEvent` marks
  them; `SpringDomainEventAdapter` in bootstrap publishes them as Spring application events). Use cases
  publish inside the mutation's transaction after the store accepted it; a failed publication rolls the
  mutation back. Listeners (none yet) react after the commit with `@ApplicationModuleListener`; a
  durable event publication registry needs its own table and comes with the first listener.
- **Fuzzy search** (`CompanyRepository`) matches a name that is similar as a whole (`%`), similar to a
  word of it (`<%`, so "acme" finds "ACME Robotics GmbH"), or contains the text (`ILIKE` with `%`, `_`
  and `\` escaped), all served by the trigram index; best match first (word similarity, similarity,
  then name and id).
- **Changelog** entries name changed detail fields with values; research notes and preference reasons
  are free text, so the description only says they changed. A company delete records the company (with
  its name) and one entry per cascaded contact (ids only, description "Deleted with its company").

Further rules the sources and description snapshots contract adds (#78, decisions in ADR-0046):

- **Child entities others record** (sources, snapshots) are written through a port of their own and are not
  a version of the aggregate: adding one keeps `version` and `updatedAt`, like read/unread, so imports and
  scanners never make the user's next save a `409`. The aggregate reads them (`Application.sources`) and
  enforces their limit; each is its own changelog entity.
- **Derived columns are checked against the domain's own computation** (`content_hash` equals the SHA-256
  of the stored text), never against a looser or different rule, so the check is exactly as strict as the domain.
- **Immutable rows** (frozen snapshots) are enforced by a `BEFORE UPDATE` row trigger that allows only the
  one permitted change and raises with `CONSTRAINT = '<name>'` (and the name in its message), so repositories
  and schema tests treat it like a named constraint. Restores (`TRUNCATE`, `COPY`) are unaffected; a migration
  that must rewrite such rows disables and re-enables the trigger within itself (ADR-0046).
- **Lengths:** the domain counts UTF-16 units (`String.length`), `char_length` counts code points, so a text
  with characters outside the BMP is shorter for the database: the database stays at most as strict.
- **Links** in every context use `shared.domain.text.WebAddress` (the companies context keeps its own copy
  until it is next touched). A link another party gave us is stored, never fetched outside the SSRF guard, and
  left out of changelog entries, since it may carry personal tracking parameters; `WebAddress.toString()`
  prints only the host (use `value` for the link).
- **Untrusted text** (postings) is stored as found after the text rules (NFC, trimmed, line breaks as `\n`,
  no U+0000, a length limit) and never printed by `toString()`.

## Consequences

- Each use case costs one small interface, but feature PRs, MCP tools and the UI start from the
  contract without waiting for each other.
- Clients read before they write and handle `409` by reloading.
- A contract PR changes no behaviour: until its feature PR lands, its endpoints answer 501 (the search
  already answers 400 for bad paging).
- Markdown the API returns (research notes, the AI profile) is untrusted; the UI renders it sanitised
  (#108).
