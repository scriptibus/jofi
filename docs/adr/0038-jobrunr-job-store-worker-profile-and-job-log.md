<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0038: JobRunr job store, worker profile and job log

- Status: accepted
- Date: 2026-09-30
- Source: issue #17 (M0-3); refines ADR-0010, ADR-0009, ADR-0032; docs/spec/04-tech-stack-proposal.md §3, §3.1, §7;
  threat model T4, T8

## Context

ADR-0010 chose JobRunr with PostgreSQL storage in a `worker` container. #17 needs the details: which
JobRunr artifacts, who creates its tables (ADR-0009 says Flyway owns the schema), how `app` is kept
from running jobs, what the stored job JSON may contain and instantiate, how retries, recurring
schedules and missed runs behave, and how the user sees the job log without JobRunr's dashboard.

## Decision

### Artifacts and licence

- **JobRunr 8.8.2** (latest stable on Maven Central, 2026-08-21; 9.0.0 is still beta), open-source edition
  only: `org.jobrunr:jobrunr` in `adapters/jobs`, `org.jobrunr:jobrunr-spring-boot-4-starter` (the Spring
  Boot 4 starter, since 8.3.0) in `bootstrap`. Licence LGPL-3.0-or-later (on the allowlist, ADR-0026); the
  poms also name a commercial licence, which only applies to JobRunr Pro. No Pro feature is used (no
  transactional enqueue, no dashboard auth, no Flyway export, no rate limiters, no job chaining).
- JobRunr's Gradle metadata imports `jobrunr-bom`, which pins newer Jackson, logback, HikariCP, Micrometer,
  pgjdbc and Testcontainers than Spring Boot does. Every JobRunr dependency excludes it; the Boot BOM
  decides versions.
- Docs consulted: https://www.jobrunr.io/en/documentation/configuration/spring/,
  https://www.jobrunr.io/en/documentation/storage/, https://www.jobrunr.io/en/documentation/background-methods/dealing-with-exceptions/,
  https://www.jobrunr.io/en/documentation/background-methods/recurring-jobs/, the 8.8.2 sources (`DatabaseCreator`,
  `ProcessRecurringJobsTask`, `RecurringJob.toScheduledJobs`, `RetryFilter`, `Jackson3JsonMapper`).

### Schema: Flyway, not JobRunr

- JobRunr's tables come from our migration `V20260930064000__jobrunr_job_store.sql`: the end state of JobRunr's
  PostgreSQL migrations v001 to v016, generated with JobRunr's own `DatabaseSqlMigrationFileProvider` and folded
  into one script (without JobRunr's `jobrunr_migrations` bookkeeping table).
- JobRunr runs with `DatabaseOptions.NO_VALIDATE` (our `storageProvider` bean): it neither creates nor
  validates tables. `SKIP_CREATE` would validate them, which opens a connection while the context starts; the
  image's AOT training run has no database, so it would fail the image build. `JobRunrSchemaTest` runs
  JobRunr's own migrations on a second database and compares columns, indexes, the statistics view and the
  seeded metadata with ours; a JobRunr upgrade that adds a migration fails it until a Flyway migration adds
  the difference.
- The job store is operational state, not user data: excluded from export/import (#26), like `spring_session*`.
  Job arguments are ids, so nothing is lost: after a restore the queue is empty and `app` registers its
  recurring schedules again.

### Who runs jobs

- `jobrunr.background-job-server.enabled` is `false` in `application.yaml` and `true` only in
  `application-worker.yaml` (compose's `worker`, `SPRING_PROFILES_ACTIVE=worker`, `worker-count: 4`). `app`
  only writes to the job store through `JobSchedulerPort`; the integration test `BackgroundJobsTest` starts
  both profiles on one database and proves the split (no server bean and no server row for `app`, every run
  in the `worker` profile).
- Registering recurring schedules is an `app` startup runner (`HousekeepingStartup`, `@Profile("!worker")`),
  like the auth startup checks (ADR-0035).
- JobRunr's dashboard stays disabled (`jobrunr.dashboard.enabled: false`, the default made explicit): it
  would open a second web server on port 8000 without our login. `jobrunr.miscellaneous.allow-anonymous-data-usage`
  is `false` (no telemetry). Carbon-aware scheduling, which would call `api.jobrunr.io`, stays off; a cron
  with a carbon-aware margin cannot be expressed through `CronSchedule` (exactly five fields).

### One job type on the wire, ids only

- Every job is `JofiJobRequest(type, arguments, maxRandomDelaySeconds)` handled by `JofiJobRequestHandler`,
  which dispatches on `type` to the `JobHandlerPort` bean of that type (an inbound port, implemented by a
  `*JobAdapter` that calls one use case). JobRunr names the job after its type, so logs and the job log never
  show arguments; `JofiJobRequest.toString()` prints argument names only.
- A handler returns a `JobOutcome` (`Done`, `Retry(reason)`, `GiveUp(reason)`); the reason is a
  `FailureReason` slug such as `storage-failure`. The handler turns failures into a `JobRunFailedException`
  whose message is that code and which has no cause, because JobRunr stores exception type, message and stack
  trace in the job JSON. An exception from a handler becomes `Retry(unexpected-error)`, logged by type only.
- Retries: JobRunr's exponential backoff, 10 retries with seed 3 (3 s, 9 s, ... about 16 h for the last).
  `GiveUp` sets JobRunr's do-not-retry flag. Failed jobs stay in the store until deleted, and the data they
  were triggered for stays where it is (arguments are ids).

### The stored JSON cannot instantiate arbitrary classes or call arbitrary code

JobRunr's job JSON names the class and method to call and the classes of its parameters. Two layers keep a
tampered row (e.g. after SQL injection elsewhere) from escalating to code execution in the worker, which
holds the master keyset:

1. The Jackson 3 mapper (`Jackson3JsonMapper` with our builder) gets a deserializer modifier that refuses to
   build any bean class outside `org.jobrunr.`, `java.` and `JofiJobRequest`. JobRunr already restricts
   polymorphic `@class` ids to its own types; the modifier also covers job parameters, whose class name JobRunr
   reads from the JSON. A refused parameter becomes "not deserializable" instead of being instantiated.
2. `AllowlistJobMapper` (our `JobMapper` bean) checks every job and recurring job read from the store: only
   `JofiJobRequestHandler.run(JofiJobRequest)` passes. Anything else (a lambda, a static method, another
   class) keeps id, state and history but runs as the `rejected-job` type, which fails without retry. A
   tampered row neither runs nor blocks the queue. `JobStoreTest` covers both layers.

### Recurring jobs

- `JobSchedulerPort.scheduleRecurring` takes a `CronSchedule`: five cron fields, a zone, and a random delay of
  up to one hour (proposal §7: scanners use up to 15 minutes). With a delay, the recurring run only schedules
  the actual work as a one-off job at a random time within the delay.
- **A missed run runs once, not once per slot.** JobRunr schedules the next run of a recurring job ahead of
  time. If the worker is down at that slot, the scheduled run is overdue when it starts again and runs once;
  JobRunr does not create jobs for the other missed slots. This only holds while the pending run survives, so
  registering an unchanged schedule again (every `app` start) is a no-op; a changed or cancelled schedule
  deletes its pending runs. `BackgroundJobsTest` simulates five hours of downtime with the hourly session
  cleanup and asserts exactly one run.
- Housekeeping: the hourly `session-cleanup` job deletes sessions past their idle timeout and records the
  count in the changelog as `Actor.System("session-cleanup")` (no entry when nothing was deleted). Spring
  Session's own cleanup in `app` is switched off (`spring.session.jdbc.cleanup-cron: "-"`).

### Job log API

`GET /api/system/jobs?status=&page=&size=` (behind the login like every `/api` endpoint, ADR-0035) returns
`name` (the job type), `status`, `attempts`, `createdAt`, `updatedAt` and `lastFailure` (the reason code),
newest change first, plus `total`. JobRunr pages per state, so the log across all states merges the first
`offset + size` jobs of each state; a page must end within the newest 1000 jobs. Arguments, exception
messages and stack traces are never part of the response. The UI is #69.

## Consequences

- Features enqueue through `JobSchedulerPort` and add a `JobHandlerPort` adapter per job type; they never see
  JobRunr. A job type is a slug that also names the changelog actor of its mutations.
- Enqueueing is not part of the caller's database transaction (JobRunr opens its own connection; transactional
  enqueue is a Pro feature). A use case that must not lose a job commits its mutation first and enqueues
  afterwards; a job whose target is gone reports `GiveUp`.
- JobRunr upgrades need a look at its new migrations (`JobRunrSchemaTest` fails) and at the `jobrunr-bom`
  exclusion.
- JobRunr keeps succeeded jobs 36 h and deleted ones 72 h (its defaults); the job log shows what is left.
