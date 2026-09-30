-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Tasks with exact or rough timing and custom countdowns (spec §10.1, §10.2, #80, ADR-0049). Checks mirror `Task`,
-- `TaskDetails`, `TaskTiming`, `TaskOrigin` and `CountdownDetails` and are never stricter (ADR-0041): whitespace is
-- ASCII `[ \t\n\r\f\v]`, never `\s`; letter ranges are ASCII code points; dates and times have no range here (the
-- domain's is 2000 to 2100); the time zone is not looked up, since PostgreSQL's zone names need not match Java's.
-- Every constraint is named.

CREATE TABLE task (
    id               uuid        CONSTRAINT task_pk PRIMARY KEY,
    title            text        NOT NULL CONSTRAINT task_title_valid CHECK (
        title ~ '[^ \t\n\r\f\v]' AND title !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(title) <= 300),
    -- Markdown written by the user; may describe other people.
    notes            text        CONSTRAINT task_notes_valid CHECK (
        notes ~ '[^ \t\n\r\f\v]' AND notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(notes) <= 10000),
    -- Exact timing: the instant it is due and the zone it was planned in (an IANA id or an offset, as Java names it).
    due_at           timestamptz,
    time_zone        text        CONSTRAINT task_time_zone_valid
        CHECK (time_zone ~ '^[^ \t\n\r\f\v]+$' AND char_length(time_zone) <= 64),
    -- Rough timing: a day, the week from a Monday, the month from its first day, or someday (no first day).
    bucket_span      text        CONSTRAINT task_bucket_span_valid
        CHECK (bucket_span IN ('DAY', 'WEEK', 'MONTH', 'SOMEDAY')),
    bucket_starts_on date,
    -- At most one link; deleting what it points to clears it and keeps the task (ADR-0049, ADR-0041's rule for
    -- optional references to `contact`, so contact and company deletes are never blocked).
    application_id   uuid
        CONSTRAINT task_application_fk REFERENCES application (id) ON DELETE SET NULL,
    company_id       uuid
        CONSTRAINT task_company_fk REFERENCES company (id) ON DELETE SET NULL,
    contact_id       uuid
        CONSTRAINT task_contact_fk REFERENCES contact (id) ON DELETE SET NULL,
    origin           text        NOT NULL CONSTRAINT task_origin_valid CHECK (origin IN ('MANUAL', 'CHAT', 'SUGGESTED')),
    -- A suggestion's rule (kebab-case, also the `SYSTEM` actor's name) and what it suggested it for.
    suggestion_rule  text        CONSTRAINT task_suggestion_rule_valid
        CHECK (suggestion_rule ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(suggestion_rule) <= 64),
    suggestion_key   text        CONSTRAINT task_suggestion_key_valid
        CHECK (suggestion_key ~ '^[^ \t\n\r\f\v]+$' AND char_length(suggestion_key) <= 200),
    state            text        NOT NULL CONSTRAINT task_state_valid
        CHECK (state IN ('SUGGESTED', 'OPEN', 'DONE', 'DISMISSED')),
    completed_at     timestamptz,
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version          bigint      NOT NULL DEFAULT 0 CONSTRAINT task_version_valid CHECK (version >= 0),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,

    CONSTRAINT task_timing_valid CHECK (
        (due_at IS NOT NULL AND time_zone IS NOT NULL AND bucket_span IS NULL AND bucket_starts_on IS NULL)
        OR (due_at IS NULL AND time_zone IS NULL AND bucket_span IS NOT NULL
            AND (bucket_span = 'SOMEDAY') = (bucket_starts_on IS NULL))),
    CONSTRAINT task_bucket_start_valid CHECK (
        (bucket_span IS DISTINCT FROM 'WEEK' OR extract(isodow FROM bucket_starts_on) = 1)
        AND (bucket_span IS DISTINCT FROM 'MONTH' OR extract(day FROM bucket_starts_on) = 1)),
    CONSTRAINT task_single_link CHECK (num_nonnulls(application_id, company_id, contact_id) <= 1),
    CONSTRAINT task_suggestion_matches_origin CHECK (
        (origin = 'SUGGESTED') = (suggestion_rule IS NOT NULL) AND (suggestion_rule IS NULL) = (suggestion_key IS NULL)),
    CONSTRAINT task_state_matches_origin CHECK (state IN ('OPEN', 'DONE') OR origin = 'SUGGESTED'),
    CONSTRAINT task_completed_matches_state CHECK ((state = 'DONE') = (completed_at IS NOT NULL)),
    CONSTRAINT task_updated_after_created CHECK (updated_at >= created_at),
    -- A rule suggests once per key, and a dismissed suggestion stays (#95); NULLs (direct tasks) never collide.
    CONSTRAINT task_suggestion_unique UNIQUE (suggestion_rule, suggestion_key)
);

-- The task list (#94) and the suggestions (#95) by state.
CREATE INDEX task_state_idx ON task (state);

-- A linked entity's tasks (its timeline, #94) and the `SET NULL` of its delete.
CREATE INDEX task_application_idx ON task (application_id) WHERE application_id IS NOT NULL;
CREATE INDEX task_company_idx ON task (company_id) WHERE company_id IS NOT NULL;
CREATE INDEX task_contact_idx ON task (contact_id) WHERE contact_id IS NOT NULL;

-- Custom countdowns for the dashboard (spec §10.1); the derived ones are queries (#112). The target is a date without
-- a zone, counted on the viewer's calendar.
CREATE TABLE countdown (
    id          uuid        CONSTRAINT countdown_pk PRIMARY KEY,
    title       text        NOT NULL CONSTRAINT countdown_title_valid CHECK (
        title ~ '[^ \t\n\r\f\v]' AND title !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(title) <= 200),
    target_date date        NOT NULL,
    version     bigint      NOT NULL DEFAULT 0 CONSTRAINT countdown_version_valid CHECK (version >= 0),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,

    CONSTRAINT countdown_updated_after_created CHECK (updated_at >= created_at)
);

CREATE INDEX countdown_target_date_idx ON countdown (target_date);
