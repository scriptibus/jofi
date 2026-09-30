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
- Every new table or file must be covered by export/import (ADR-0042): add the table to
  `BackupTables.EXPORTED` and a seed row with awkward values to `DatabaseBackupRepositoryTest`, or to
  `BackupTables.EXCLUDED` with a reason. The test enumerates the generated jOOQ schema and fails for a
  table in neither list or one it cannot round-trip.
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
  sessions are bound to (`UserAccountRepository`). Exported (ADR-0042).
- `master_key_check` (#16): at most one row, a Tink ciphertext of a fixed text proving which master
  keyset encrypted `secret` (`MasterKeyRecordRepository`, ADR-0035). Exported; a restore checks it
  against the backup's keyset (ADR-0042).
- `ai_provider_config`, `ai_model_assignment`, `ai_model_capability`, `ai_monthly_budget`,
  `ai_cost_entry` (#11, repositories in `setup.adapter.persistence` since #20, ADR-0043): read by the
  AI gateway on every call. `ai_cost_entry` is append-only; `cost_micros` NULL means the cost is
  unknown (no list price, or no usage reported), and sums skip it. The cost reports (#24) aggregate in SQL
  over `ai_cost_entry_occurred_at_idx`: per task, provider kind and model, and per UTC month
  (`to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM')`, independent of the session's time zone). A provider with assignments
  cannot be deleted (`InUse`).
- `spring_session`, `spring_session_attributes`: login sessions, managed by Spring Session JDBC (schema
  copied from spring-session-jdbc 4.1.1). Ephemeral bearer credentials: **excluded from export/import**
  (ADR-0042); a restore empties them, so it ends every session. Never log their ids.
- `ExpiredSessionsRepository` (`ExpiredSessionsPort`, #17): deletes sessions past their idle timeout (the
  statement Spring Session's own cleanup runs, which is switched off in `app`); the hourly `session-cleanup`
  worker job calls it and records the count in the changelog.
- `jobrunr_jobs`, `jobrunr_recurring_jobs`, `jobrunr_backgroundjobservers`, `jobrunr_metadata` and the view
  `jobrunr_jobs_stats` (#17, ADR-0038): JobRunr's job store, created by our migration (the end state of
  JobRunr 8.8.2's own migrations; JobRunr runs with `NO_VALIDATE` and never touches the schema).
  `JobRunrSchemaTest` compares it with the schema JobRunr's migrations create: after a JobRunr upgrade that
  fails, add a migration with the difference. Only JobRunr reads and writes these tables (no jOOQ
  repositories). Operational state: **excluded from export/import** (ADR-0042), a restore starts with an empty
  queue and `app` registers its recurring schedules again.
- `TransactionAdapter` (`TransactionPort`): Spring's JDBC transaction around several repository calls;
  jOOQ joins it.
- `DatabaseBackupRepository` (`DatabaseBackupPort`, ADR-0042): dumps the exported tables with
  PostgreSQL `COPY ... TO STDOUT (FORMAT csv, HEADER true)` (pgjdbc's `CopyManager`, which is why the
  driver is a compile dependency) from one `REPEATABLE READ, READ ONLY` snapshot, and restores them in
  the caller's transaction: `TRUNCATE` of the exported and session tables, `COPY ... FROM STDIN (FORMAT
  csv, HEADER match)` in foreign-key order, row counts checked, identity sequences continued. It acts
  only with the gate's `Confirmed` for exactly that backup.
  `ScratchMigration` migrates an older backup's dumps in a scratch database `jofi_restore_<id>` (Flyway to
  the backup's version, load, Flyway to latest, dump again, drop), so the app's database is never touched;
  it needs the right to create databases. Migrations therefore must also work on restored old data.
  Leftover scratch databases are dropped at startup and before each migration (`dropScratchDatabases`).
- **No SQL built from row values**: no `EXECUTE` in functions and no `DO` blocks that assemble SQL from
  table contents. A restored backup (or a migrated one in the scratch database) is untrusted data, and
  migrations run on it.
- `ai_provider_config` (base URL without credentials, query or fragment; one secret per provider),
  `ai_model_capability` (per provider + model, deleted with the provider), `ai_model_assignment`
  (one row per `AiTask`: provider + model only), `ai_cost_entry` (append-only by trigger, integer
  micros in USD, provider kind snapshot, no FK to the provider so history survives its deletion),
  `ai_monthly_budget` (single row, USD). Check constraints mirror the `setup` domain invariants and
  enum names; `SetupSchemaTest` proves them. `ProviderConfigRepository.delete` takes the confirmation
  proof (`ProviderId.DELETE_OPERATION`, ADR-0039); the use case deletes the provider's secret after the
  row that references it.
- Other contexts' repositories live in `<context>.adapter.persistence` and may use the shared
  persistence code in `shared.adapter.persistence`: the generated jOOQ code, `ActorColumns` (the actor
  column pair) and `violatedConstraint()` (the one exemption from adapter independence, ADR-0032; widened
  from the jOOQ code alone in #82, since the status history stores actors like the changelog).

## `companies` tables (#73, #74, ADR-0041)

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
- `CompanyRepository` (`CompanyRepositoryPort`, #88): `update` stores only if the stored `version` is one
  below the new one (`VersionConflict` otherwise, `NotFound` without the row); `delete` checks the
  `Confirmed` proof (ADR-0039) and maps `application_company_fk` by name (the PSQL error's constraint, anywhere in the
  cause chain since #89, so Spring's exception translation does not hide it) to `HasApplications`; `findContactIds` lists the contacts the delete cascades to. `search` matches names
  with pg_trgm (`%`, `<%`) or an escaped `ILIKE`, all served by `company_name_trgm_idx`, best match first
  (word similarity, similarity, name, id). `CompanyRepositoryTest` proves each of these.
- `ApplicationCountsRepository` (`applications.adapter.persistence`) implements the companies context's
  `ApplicationCountsPort` (`companies.application.port.spi`, ADR-0041): applications per company, grouped
  over `application_company_idx`.
- Changelog: research notes and profiles are free text that may hold personal data; record that they
  changed (in the description, e.g. "Research notes edited"), never their text in a `FieldChange`,
  until redaction exists (#52).

- `contact` and `contact_channel` (#74, spec §5, §13): contact persons (trimmed name, role, relationship
  notes, optional `company_id` with `contact_company_fk` `ON DELETE CASCADE`, `version`) and their ordered
  channels (`position` 0-19, `kind` `EMAIL`/`PHONE`/`WEB`/`OTHER`, `value` as entered, optional `label`;
  deleted with the contact by `contact_channel_contact_fk`). Named check constraints mirror `ContactDetails`
  and `ContactChannel`, never stricter: per-kind length limits, email needs text on both sides of its last
  `@`, web links are absolute http(s) without user info; whitespace, digits and control characters are
  the domain's job (Unicode classes), and so are exact duplicate channels (a unique index on values up to
  2048 characters could exceed the index row size). `ContactSchemaTest` proves every constraint, every
  limit, the names and both cascades. `contact_company_idx` serves the company filter, the company
  delete's count and its cascade; `contact_name_trgm_idx` fuzzy name search.
- Contacts are **third-party personal data**: user data **covered by export/import** (#26); never log a
  row, and changelog entries for contacts name the changed fields, never their values (the changelog is
  append-only, and a deleted contact must leave nothing personal behind). `ContactRepository`
  (`ContactRepositoryPort`, #89) maps a violation of `contact_company_fk` on insert or update to
  `CompanyNotFound` by its name, found anywhere in the cause chain (`violatedConstraint()`: the app's
  `DSLContext` throws Spring's translated exceptions, plain jOOQ its own); `update` stores only on top of
  the version one below; `delete` checks the `Confirmed` proof; `search` uses the same trigram matching as
  companies (`NameQuery`, `contact_name_trgm_idx`) and loads the page's channels in one query.
  `ContactRepositoryTest` proves each of these.
- **Complete deletion (DSGVO Art. 17, spec §13)**: a confirmed contact delete removes the `contact` row;
  `contact_channel` rows and `application_contact` links go with it by `ON DELETE CASCADE`. Nothing is
  soft-deleted. What remains are changelog entries with ids only: the contact's own (field names at most,
  never values) and one per linked application (`FieldChange("contacts", <contact id>, null)`). The pending
  confirmation (in memory, ADR-0039) holds the name until it is redeemed or expires. Backups made before
  the delete still contain the contact until the user deletes them.
- `LinkedApplicationsRepository` (`applications.adapter.persistence`) implements the companies context's
  `LinkedApplicationsPort` (`companies.application.port.spi`, ADR-0041): the applications linked to a
  contact as `application` entity refs, over `application_contact_contact_idx`.
- Foreign keys **to** `contact` from other contexts (ADR-0041): link-table rows (e.g. application links,
  #90) use `ON DELETE CASCADE`; an optional reference in an entity's own row (e.g. `task.contact_id`,
  #93) uses `ON DELETE SET NULL`; never `RESTRICT`/`NO ACTION`, which would block company deletes
  through `contact_company_fk`. They also react to `ContactDeleted`.
- Channels have no stable id (`(contact_id, position)` changes on every edit), so nothing may reference a
  single channel; the repository replaces all of a contact's channels (delete, then insert) in the
  version-checked update.
- Text columns never hold U+0000 (PostgreSQL `text` rejects it); the domain rejects it first, so a valid
  entity never fails to store.

## `applications` tables (#76, ADR-0041)

- `application` (spec §6.1): title, `company_id` (**`ON DELETE RESTRICT`**, `application_company_fk`, so a
  company with applications cannot be deleted), location, remote share (percent), employment type,
  seniority, deadline, how applied + portal notes, the pay band (gross `pay_min`/`pay_max` `numeric(12,2)`,
  ISO 4217 `pay_currency`, `pay_period`, `pay_source` with `pay_estimate_basis`/`_confidence` exactly for
  `ESTIMATED`), language & tone (BCP 47 `posting_language`/`application_language`, canonical case from
  the domain but any case accepted here; a null application language follows the posting's; `form_of_address`, `tone`), the decline/rejection reason
  (category + text), the offer (`offer_*`, salary as amount + currency + period together), `unread`,
  `want_score`/`fit_score` placeholders (`numeric(2,1)`, 0 to 5), `status` (#77, ADR-0044) and `version`.
  The decline reason is set exactly while the status is `DECLINED` or `REJECTED`
  (`application_decline_reason_matches_status`, added `NOT VALID` and validated after the backfill).
  Named check constraints mirror the domain, never
  stricter: letter ranges are ASCII code points, the "at least one offer detail" rule and the 50-contact
  limit stay in the domain. `ApplicationSchemaTest` proves every constraint, every limit, every enum
  constant and the names. `application_company_idx` serves the company filter, counts and the RESTRICT
  check; `application_title_trgm_idx` fuzzy title search; `application_unread_idx` the few unread rows.
- `application_contact` (#90): links to contacts, PK `(application_id, contact_id)`, both foreign keys
  `ON DELETE CASCADE` (`application_contact_application_fk`, `application_contact_contact_fk`), so deleting
  a contact or its company unlinks it and is never blocked. `ApplicationContactSchemaTest` proves both
  cascades and the RESTRICT on `company`. `application_contact_contact_idx` serves "applications per contact".
- `application_status_change` (#77, ADR-0044): the status history, one row per move in order (identity
  `id`), `from_status` NULL only for the first entry, optional `reason`, `decline_category` exactly for
  `DECLINED`/`REJECTED` entries, the actor as in `changelog_entry`, `changed_at`; deleted with the
  application (`application_status_change_application_fk`, `ON DELETE CASCADE`). The migration gave every
  existing application one entry (`SYSTEM` `status-history-backfill`). The transition matrix is the
  domain's job. `ApplicationStatusChangeSchemaTest` proves the constraints, `ApplicationStatusMigrationTest`
  the backfill. User data: **covered by export/import**.
- `ApplicationRepository` implements `ApplicationRepositoryPort` (#82; `search` answers a `StorageFailure`
  until #83 builds the list, whose endpoint answers 501 until then). **No write overwrites columns it
  does not own** (lost updates): `updateDetails` writes only the detail columns, `version` and `updated_at`
  (never the status, the decline reason, `unread`, the scores or the links); `changeStatus` writes only
  `status`, the decline reason, `version` and `updated_at` and appends the history row, both or neither; `replaceContacts` writes `version`/`updated_at` and rewrites
  `application_contact` only when the stored set differs; both store only if the stored `version` is one
  below the new one. `setUnread` changes only the flag (no version). `delete` checks the `Confirmed` proof.
  `add` stores the row, its links and the first history entry (`StatusChange.initial`); `findById` reads the
  links and the sources (oldest first); `snapshotCount` counts the snapshots the delete cascades to. Each write sets only
  its columns in an `ApplicationRecord` (`ApplicationRecords.detailsRecord`, `statusRecord`,
  `versionRecord`), and jOOQ updates only the fields a record has set. `ApplicationRepositoryTest` proves each
  write leaves the other columns as they were (and that an unchanged link set keeps its rows). It maps
  `application_company_fk` to `CompanyNotFound` and `application_contact_contact_fk` to `ContactNotFound` by
  name, anywhere in the cause chain (`violatedConstraint()`, `shared.adapter.persistence`).
- Portal notes, reasons and offer text are the user's free text: changelog entries name the changed
  fields, never the text (#52). User data: **covered by export/import** (#26, #134).
- `application_source` (#78, ADR-0046): where a job was found, deleted with its application
  (`application_source_application_fk`, `ON DELETE CASCADE`): `kind` (`SCANNER`/`URL`/`MANUAL_CHAT`), `original_url`
  (as `company.website`; required for `URL`, `application_source_url_matches_kind`), `discovered_at`,
  `offline_since` (NULL while online, not before discovery). Not unique per link (a careers page can list several
  jobs); `application_source_original_url_idx` is a **hash** index for the URL import's lookup (#97), since a
  btree row could exceed its size limit. The 50-sources limit stays in the domain; `add` re-counts after locking
  the application row with `FOR NO KEY UPDATE`. `discovered_at` has no lower bound here (the domain's is 2000-01-01). Written only by
  `ApplicationSourceRepositoryPort` (#86, #96), read with the application; adding one is no new version.
- `application_description_snapshot` (#78, ADR-0046): one row per version of a source's description, deleted with
  its source. `description` is untrusted posting text (at most 100,000 characters), `content_hash` must equal
  `encode(sha256(convert_to(description, 'UTF8')), 'hex')` (`..._content_hash_matches`, what `ContentHash`
  computes), `reason` (`DISCOVERY`/`CHANGE_DETECTED`/`MANUAL`), `captured_at`, `frozen_at` (set once, not before
  capture). The trigger `application_description_snapshot_immutable` rejects every `UPDATE` but freezing an
  unfrozen row with nothing else changing; it raises with that name as its constraint. Only the first freeze
  counts (`freeze` skips sources that have a frozen snapshot) and it runs in the status change's transaction; a
  source added after applying stores its discovery snapshot frozen (ADR-0046). **Escape hatch:** a migration that
  must rewrite snapshots disables the trigger, updates (keeping `content_hash` equal to
  `encode(sha256(convert_to(description, 'UTF8')), 'hex')`) and enables it again, all within that one migration;
  nothing else disables it. Repositories never log
  the text, and changelog entries never hold it. `ApplicationSourceSchemaTest` proves the constraints, the
  limits, the hash, the trigger and both cascades. User data: **covered by export/import**.

## Changelog entity types

`EntityRef.type` is stored in every changelog entry, so these names never change. Each is a constant on
the aggregate's id type, which also builds the `EntityRef` (`toEntityRef()`).

| Entity type | Aggregate | Constant |
|---|---|---|
| `company` | `companies.domain.Company` | `CompanyId.ENTITY_TYPE` |
| `contact` | `companies.domain.Contact` | `ContactId.ENTITY_TYPE` |
| `ai_provider` | `setup.domain.ProviderConfig` (also its models' capability corrections and refreshes) | `ProviderId.ENTITY_TYPE` |
| `ai_model_assignment` | `setup.domain.ModelAssignment`, one entity per task (id = task name) | `ModelAssignment.ENTITY_TYPE` |
| `ai_monthly_budget` | `setup.domain.MonthlyBudget`, a single entity (id `monthly`) | `MonthlyBudget.ENTITY_TYPE` |
| `application` | `applications.domain.Application` | `ApplicationId.ENTITY_TYPE` |
| `application_source` | `applications.domain.ApplicationSource` | `SourceId.ENTITY_TYPE` |
| `description_snapshot` | `applications.domain.DescriptionSnapshot` (recorded and frozen) | `SnapshotId.ENTITY_TYPE` |
| `interview` | `applications.domain.Interview` (with its participants) | `InterviewId.ENTITY_TYPE` |
| `task` | `tasks.domain.Task` (also its suggestions) | `TaskId.ENTITY_TYPE` |
| `countdown` | `tasks.domain.Countdown` (custom countdowns only) | `CountdownId.ENTITY_TYPE` |

## Tests

Plain JUnit against a real PostgreSQL (`PostgresTestDatabase`: one container per test JVM, Flyway
`clean` + `migrate` before each test). Never use an in-memory database or mock the `DSLContext`.
