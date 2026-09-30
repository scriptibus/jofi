<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/jobs

Background jobs on JobRunr 8 (open-source edition) with PostgreSQL storage (ADR-0010, ADR-0038). The
only module that uses JobRunr types, apart from the wiring in `bootstrap` (`shared.config.JobsConfiguration`).
Packages: `shared.adapter.jobs` (the job store) and `<context>.adapter.jobs` (job handlers).

## What is here

- `JobRunrJobSchedulerAdapter` (`JobSchedulerPort`): enqueue, recurring schedules (cron + zone + random
  delay), cancel. Registering an unchanged schedule is a no-op, so the run JobRunr scheduled ahead survives
  restarts (that run is the one catch-up after downtime); a changed or cancelled schedule deletes its pending
  runs.
- `JobRunrJobLogAdapter` (`JobLogPort`) and `JobLogMapping`: the job log, merged across JobRunr's states.
  Failure reasons are our reason codes only; any other exception shows as `unexpected-error`.
- `JofiJobRequest` + `JofiJobRequestHandler`: the one job payload (type, id arguments, random delay) and the
  dispatcher to the `JobHandlerPort` of that type. Failures become `JobRunFailedException(reason code)`
  without a cause, because JobRunr stores message and stack trace.
- `JobStore`, `JobJsonGuard`, `AllowlistJobMapper`: the storage provider (Flyway owns the tables,
  `NO_VALIDATE`, no connection at startup) and three layers against tampered rows (ADR-0038): the raw JSON is
  checked with a plain tree mapper before JobRunr loads any class it names (handler, method, parameter and an
  exact list of type ids); JobRunr's Jackson 3 mapper builds no class outside JobRunr's model, `JofiJobRequest`
  and an exact list of JDK types (beans, maps, collections, enums, arrays); anything foreign or unreadable is
  quarantined as `rejected-job` with its id and state. A new job-JSON type (e.g. new JobRunr metadata after an
  upgrade) must be added to `JobJsonGuard.TYPE_IDS` deliberately; `JobJsonGuardTest` shows what is written.
- `system.adapter.jobs.SessionCleanupJobAdapter`: the hourly housekeeping job.

## Rules

- **Add a job**: a job type slug and its request in the owning context's `domain`, a use case, and a
  `<Name>JobAdapter` (`@Component`, implements `JobHandlerPort`) in `<context>.adapter.jobs` that reads the
  arguments and calls that one use case. It returns `JobOutcome.Done`, `Retry(reason)` for temporary
  failures or `GiveUp(reason)` for permanent ones; it never throws.
- **Arguments are ids** (and small settings): never secrets, personal data or text. They are stored in
  `jobrunr_jobs` and survive failures, so the job can run again.
- **Reason codes are slugs** (`FailureReason`), never exception messages.
- Mutations inside a job write a changelog entry with the job's actor (`Actor.System(<job type>)`, or
  `Actor.Scanner(<name>)` for scanners), in the same transaction as the change.
- Never use JobRunr lambdas (`BackgroundJob.enqueue { ... }`), `@Job`/`@Recurring`/`@AsyncJob`, `JobScheduler`
  or the dashboard: the allowlist rejects every job that is not a `JofiJobRequest`, and `AdapterRulesTest` fails
  the build. JobRunr types stay in `shared.adapter.jobs` (plus the wiring in `bootstrap`'s `shared.config`).
- Enqueueing is not transactional with the caller's database work: commit first, then enqueue.

## Tests

Unit tests run against JobRunr's `InMemoryStorageProvider` behind the production mappers
(`inMemoryJobStore()`), including tampered JSON (`JobStoreTest`). The two-process behaviour (app enqueues,
worker runs, retries, missed runs) is `BackgroundJobsTest` in `bootstrap`; the schema is `JobRunrSchemaTest`
in `adapters/persistence`.
