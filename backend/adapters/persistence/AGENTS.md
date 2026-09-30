<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/persistence

Owns the database: Flyway migrations, the jOOQ code generated from them and the jOOQ
repositories that implement persistence ports (ADR-0008, ADR-0009, ADR-0030). Packages:
`io.github.scriptibus.jofi.<context>.adapter.persistence`.

## Database

PostgreSQL 18 with `vector` (pgvector) and `pg_trgm`, image `jofi.postgresImage` in
`backend/gradle.properties`, pinned by digest. The same image runs jOOQ codegen, the integration
tests and `bootTestRun`. Bump tag and digest together.

## Migrations

- `src/main/resources/db/migration/V<yyyyMMddHHmmss>__<snake_case_description>.sql`, UTC time of
  writing (e.g. `V20260930010056__baseline_extensions_and_changelog.sql`). `MigrationsTest` fails
  on any other name.
- Never edit a merged migration; add a new one. No destructive changes without a data migration
  and export/import support (the portability lens checks this).
- **Only one open PR at a time may add migrations.** The `migration-lock` CI job fails a PR that
  adds a migration while an older open PR also adds one; wait for it to merge, rebase, re-run.
  Migrations are a protected path: Lucas reviews every one.
- Every new table or file must be covered by export/import (#26 enumerates the tables from the
  generated jOOQ schema, so a table it cannot round-trip fails its test).
- **Check constraints are never stricter than the domain** (ADR-0041): whatever the domain accepts must
  be storable, or a valid entity fails with a 500. No locale-dependent logic in SQL: no `lower()`/
  `upper()`/`ILIKE` or collation-dependent comparison in constraints, and whitespace as the ASCII class
  `[ \t\n\r\f\v]`, never `\s`. Case-insensitive rules stay in the domain. Schema tests accept every
  value at **exactly** each domain limit and a sample the domain accepts (non-ASCII text, IDN hosts).
- **Name every constraint** (`CONSTRAINT <table>_<column>_valid CHECK ...`, `<table>_pk`,
  `<table>_<ref>_fk`): repositories map violations by constraint name, never by SQL state alone, and
  schema tests assert the name.
- **Never tighten a check function with `CREATE OR REPLACE`** (e.g. `company_locations_are_valid`):
  existing rows are not checked again, so data that breaks the new rule stays and fails later. Add a
  new function and constraint with `ADD CONSTRAINT ... NOT VALID`, clean the data, then `VALIDATE
  CONSTRAINT` and drop the old one. Loosening in place is fine.

## jOOQ code generation

`generateJooq` (runs before compilation) starts the pinned PostgreSQL in Testcontainers (or uses the
empty database named by `JOFI_CODEGEN_JDBC_URL`/`_USER`/`_PASSWORD`, as the container image build
does), runs all migrations from zero with Flyway, and generates Java code into
`build/generated-sources/jooq` (package `io.github.scriptibus.jofi.shared.adapter.persistence.jooq`).
The code is never committed: it always matches the migrations, and a repository that uses a
column the migrations no longer have does not compile. `MigrationsTest` also compares the generated
tables with the live migrated schema. Routines (extension and trigger functions) are not generated.
The generator lives in the `codegen` source set and has its own locked classpath.

## Repositories

- Named `*Repository` (or `*Adapter`), implement one port, annotated `@Component`, map jOOQ records
  to domain types explicitly. No business logic.
- No exception crosses a port: catch at the adapter edge and return the port's sealed result.
  Log only the operation and exception type; exception messages can contain row data (personal data).
- Store `Instant`s in `timestamptz` (microsecond precision).

## Changelog contract (`shared`)

- `ChangelogPort` (`append`, `listByEntity`, `listRecent`), implemented by `ChangelogRepository`
  on `changelog_entry`. Every mutation in every context appends one `ChangelogEntry` with the
  acting `Actor` (User, Ai, Scanner(name), ExternalClient(name), System(rule or job name)).
- Actors are stored as `actor_kind` + `actor_name`; a check constraint requires a name exactly for
  the named kinds. Field changes are a JSONB array of `{field, before, after}`.
- The table is append-only: a trigger rejects `UPDATE` and `DELETE`. Restores replace it with
  `TRUNCATE`. Before/after values can hold personal data; until erasure/redaction exists (#52),
  keep large or sensitive free text (CV bodies, notes) out of `FieldChange` values.

## Secrets and `setup` tables

- `secret`: Tink AES-GCM ciphertext per `SecretId`, written by `SecretRepository` (`SecretStorePort`)
  through `SecretCipherPort`; the key is the master keyset in the data volume (ADR-0035). Never store
  or log a key in clear text anywhere else; provider configs reference a secret id.
- `user_account` (#16): the single user, at most one row, argon2id hash only, plus the `account_id`
  sessions are bound to (`UserAccountRepository`). Covered by export/import.
- `master_key_check` (#16): at most one row, a Tink ciphertext of a fixed text proving which master
  keyset encrypted `secret` (`MasterKeyRecordRepository`, ADR-0035). Covered by export/import; it
  must travel with `secret` and the keyset.
- `spring_session`, `spring_session_attributes`: login sessions, managed by Spring Session JDBC (schema
  copied from spring-session-jdbc 4.1.1). Ephemeral bearer credentials: **excluded from export/import**
  (#26), a restore starts logged out. Never log their ids.
- `ExpiredSessionsRepository` (`ExpiredSessionsPort`, #17): deletes sessions past their idle timeout (the
  statement Spring Session's own cleanup runs, which is switched off in `app`); the hourly `session-cleanup`
  worker job calls it and records the count in the changelog.
- `jobrunr_jobs`, `jobrunr_recurring_jobs`, `jobrunr_backgroundjobservers`, `jobrunr_metadata` and the view
  `jobrunr_jobs_stats` (#17, ADR-0038): JobRunr's job store, created by our migration (the end state of
  JobRunr 8.8.2's own migrations; JobRunr runs with `NO_VALIDATE` and never touches the schema).
  `JobRunrSchemaTest` compares it with the schema JobRunr's migrations create: after a JobRunr upgrade that
  fails, add a migration with the difference. Only JobRunr reads and writes these tables (no jOOQ
  repositories). Operational state: **excluded from export/import** (#26), a restore starts with an empty
  queue and `app` registers its recurring schedules again.
- `TransactionAdapter` (`TransactionPort`): Spring's JDBC transaction around several repository calls;
  jOOQ joins it.
- `ai_provider_config` (base URL without credentials, query or fragment; one secret per provider),
  `ai_model_capability` (per provider + model, deleted with the provider), `ai_model_assignment`
  (one row per `AiTask`: provider + model only), `ai_cost_entry` (append-only by trigger, integer
  micros in USD, provider kind snapshot, no FK to the provider so history survives its deletion),
  `ai_monthly_budget` (single row, USD). Check constraints mirror the `setup` domain invariants and
  enum names; `SetupSchemaTest` proves them. Repositories come with the use cases (#19, #23, #24).
- Other contexts' repositories live in `<context>.adapter.persistence` and may use the generated
  jOOQ code in `shared.adapter.persistence.jooq` (the one exemption from adapter independence,
  ADR-0032).

## `companies` tables (#73, ADR-0041)

- `company` (spec §5): details (trimmed name, http(s) website and careers page without user info, IDN
  and underscore hosts allowed, industry, size band, ordered `locations text[]`, research notes), the AI
  profile placeholder (`profile` + `profile_generated_at`, both or neither), the preference
  (`NONE`/`FAVOURITE`/`BLACKLISTED`, a reason only with a flag) and `version` for optimistic locking.
  Named check constraints (`company_*_valid`, ...) mirror `CompanyDetails`, `WebAddress`,
  `CompanyPreference` and `CompanyProfile`, never stricter; `company_locations_are_valid` checks each
  location but only exact duplicates (case-insensitive duplicates are the domain's job).
  `CompanySchemaTest` proves every constraint, every limit and the names.
  `company_name_trgm_idx` (GIN, `gin_trgm_ops`) serves fuzzy name search and duplicate detection
  (spec §8.4); pg_trgm ignores case. User data: **covered by export/import** (#26).
- Foreign keys to `company` (decided in ADR-0041; the dependent tables come with #74 and #76):
  - `application.company_id` references `company (id)` **`ON DELETE RESTRICT`**, constraint
    `application_company_fk`: a company with applications cannot be deleted (`HasApplications`).
  - `contact.company_id` references `company (id)` **`ON DELETE CASCADE`**, constraint
    `contact_company_fk`: contacts are deleted with their company, and the delete confirmation's effect
    counts them (`"contacts" to n`), so the user sees what goes.
  - The repository maps a violation of `application_company_fk` to `HasApplications` by its name;
    any other failure is a `StorageFailure`.
- `CompanyRepositoryPort` is implemented with the use cases (#88). Its `update` stores only if the
  stored `version` is one below the new one; `delete` checks the `Confirmed` proof (ADR-0039).
- Changelog: research notes and profiles are free text that may hold personal data; record that they
  changed (in the description, e.g. "Research notes edited"), never their text in a `FieldChange`,
  until redaction exists (#52).

## Changelog entity types

`EntityRef.type` is stored in every changelog entry, so these names never change. Each is a constant on
the aggregate's id type, which also builds the `EntityRef` (`toEntityRef()`).

| Entity type | Aggregate | Constant |
|---|---|---|
| `company` | `companies.domain.Company` | `CompanyId.ENTITY_TYPE` |

## Tests

Plain JUnit against a real PostgreSQL (`PostgresTestDatabase`: one container per test JVM, Flyway
`clean` + `migrate` before each test). Never use an in-memory database or mock the `DSLContext`.
