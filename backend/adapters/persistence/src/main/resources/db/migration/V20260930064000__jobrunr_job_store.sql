-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The job store of JobRunr 8.8.2 (ADR-0010, ADR-0038, #17): background jobs, recurring schedules,
-- the heartbeat of the worker's background job server and JobRunr's counters. Flyway owns the schema
-- (ADR-0009); JobRunr runs with DatabaseOptions.NO_VALIDATE and never creates or migrates tables.
--
-- This is the end state of JobRunr's own PostgreSQL migrations v001 to v016, as printed by
-- `java -cp jobrunr-8.8.2.jar org.jobrunr.storage.sql.common.DatabaseSqlMigrationFileProvider postgres`,
-- folded into one script. JobRunr's bookkeeping table `jobrunr_migrations` is left out: nothing reads
-- it. Identifiers are unquoted as in JobRunr's scripts (PostgreSQL folds them to lower case), and the
-- timestamps are `timestamp` without time zone, because JobRunr's queries expect exactly these types.
-- JobRunrSchemaTest fails when this schema and the one JobRunr's migrations create differ, e.g. after
-- a JobRunr upgrade adds a migration: then add a new Flyway migration with the difference.
--
-- Operational state, not user data: excluded from export/import (#26). Job arguments are ids only; a
-- restore starts with an empty queue, and `app` registers the recurring schedules again at startup.

CREATE TABLE jobrunr_jobs (
    id             nchar(36) PRIMARY KEY,
    version        int          NOT NULL,
    jobAsJson      text         NOT NULL,
    jobSignature   varchar(512) NOT NULL,
    state          varchar(36)  NOT NULL,
    createdAt      timestamp    NOT NULL,
    updatedAt      timestamp    NOT NULL,
    scheduledAt    timestamp,
    recurringJobId varchar(128)
);
CREATE INDEX jobrunr_state_idx ON jobrunr_jobs (state);
CREATE INDEX jobrunr_job_signature_idx ON jobrunr_jobs (jobSignature);
CREATE INDEX jobrunr_job_created_at_idx ON jobrunr_jobs (createdAt);
CREATE INDEX jobrunr_job_scheduled_at_idx ON jobrunr_jobs (scheduledAt);
CREATE INDEX jobrunr_job_rci_idx ON jobrunr_jobs (recurringJobId);
CREATE INDEX jobrunr_jobs_state_updated_idx ON jobrunr_jobs (state ASC, updatedAt ASC);

CREATE TABLE jobrunr_recurring_jobs (
    id        nchar(128) PRIMARY KEY,
    version   int    NOT NULL,
    jobAsJson text   NOT NULL,
    createdAt bigint NOT NULL DEFAULT '0'
);
CREATE INDEX jobrunr_recurring_job_created_at_idx ON jobrunr_recurring_jobs (createdAt);

CREATE TABLE jobrunr_backgroundjobservers (
    id                         nchar(36) PRIMARY KEY,
    workerPoolSize             int           NOT NULL,
    pollIntervalInSeconds      int           NOT NULL,
    firstHeartbeat             timestamp(6)  NOT NULL,
    lastHeartbeat              timestamp(6)  NOT NULL,
    running                    int           NOT NULL,
    systemTotalMemory          bigint        NOT NULL,
    systemFreeMemory           bigint        NOT NULL,
    systemCpuLoad              numeric(3, 2) NOT NULL,
    processMaxMemory           bigint        NOT NULL,
    processFreeMemory          bigint        NOT NULL,
    processAllocatedMemory     bigint        NOT NULL,
    processCpuLoad             numeric(3, 2) NOT NULL,
    deleteSucceededJobsAfter   varchar(32),
    permanentlyDeleteJobsAfter varchar(32),
    name                       varchar(128)
);
CREATE INDEX jobrunr_bgjobsrvrs_fsthb_idx ON jobrunr_backgroundjobservers (firstHeartbeat);
CREATE INDEX jobrunr_bgjobsrvrs_lsthb_idx ON jobrunr_backgroundjobservers (lastHeartbeat);

CREATE TABLE jobrunr_metadata (
    id        varchar(156) PRIMARY KEY,
    name      varchar(92) NOT NULL,
    owner     varchar(64) NOT NULL,
    value     text        NOT NULL,
    createdAt timestamp   NOT NULL,
    updatedAt timestamp   NOT NULL
);
-- The all-time succeeded counter JobRunr's statistics read (created by its v009 from a zero counter).
INSERT INTO jobrunr_metadata (id, name, owner, value, createdAt, updatedAt)
VALUES ('succeeded-jobs-counter-cluster', 'succeeded-jobs-counter', 'cluster', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE VIEW jobrunr_jobs_stats AS
WITH job_stat_results AS (SELECT state, count(*) AS count FROM jobrunr_jobs GROUP BY state)
SELECT coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results), 0)                            AS total,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'AWAITING'), 0)   AS awaiting,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)  AS processed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
                 FROM jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'), 0)                                      AS allTimeSucceeded,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                                      AS nbrOfRecurringJobs;
