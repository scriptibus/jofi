-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Interviews and calls of an application (spec §6.1, §5, #79, ADR-0048) and who took part. Checks mirror
-- `Interview`, `InterviewDetails` and `InterviewTime` and are never stricter (ADR-0041): whitespace is ASCII
-- `[ \t\n\r\f\v]`, never `\s`; the start has no range here (the domain's is 2000 to 2100); the time zone is not
-- looked up, since PostgreSQL's zone names need not match Java's. Every constraint is named. Training sessions
-- (M5) will link to interviews through a table of their own.

CREATE TABLE interview (
    id                uuid        CONSTRAINT interview_pk PRIMARY KEY,
    -- An interview goes with its application.
    application_id    uuid        NOT NULL
        CONSTRAINT interview_application_fk REFERENCES application (id) ON DELETE CASCADE,
    -- `InterviewType` (`type` would clash with jOOQ's table API).
    kind              text        NOT NULL CONSTRAINT interview_kind_valid CHECK (
        kind IN ('PHONE_SCREEN', 'HR', 'TECHNICAL', 'CASE', 'ON_SITE', 'FINAL', 'OTHER')),
    -- The instant it starts; `time_zone` is the zone it was planned in (an IANA id or an offset, as Java names it),
    -- which shows it as the agreed wall-clock time.
    starts_at         timestamptz NOT NULL,
    time_zone         text        NOT NULL CONSTRAINT interview_time_zone_valid
        CHECK (time_zone ~ '^[^ \t\n\r\f\v]+$' AND char_length(time_zone) <= 64),
    -- Markdown written by the user; may describe other people.
    preparation_notes text        CONSTRAINT interview_preparation_notes_valid CHECK (
        preparation_notes ~ '[^ \t\n\r\f\v]' AND preparation_notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(preparation_notes) <= 50000),
    notes             text        CONSTRAINT interview_notes_valid CHECK (
        notes ~ '[^ \t\n\r\f\v]' AND notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(notes) <= 50000),
    -- NULL while it is to come or undecided.
    outcome           text        CONSTRAINT interview_outcome_valid
        CHECK (outcome IN ('PASSED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')),
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version           bigint      NOT NULL DEFAULT 0 CONSTRAINT interview_version_valid CHECK (version >= 0),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT interview_updated_after_created CHECK (updated_at >= created_at)
);

-- The interviews of an application in the order they start.
CREATE INDEX interview_application_idx ON interview (application_id, starts_at);

-- Upcoming interviews across applications (#92) and the dashboard's countdown.
CREATE INDEX interview_starts_at_idx ON interview (starts_at);

-- The contacts who took part. A row goes with either side (ADR-0041: link tables to `contact` cascade, so deleting a
-- contact or its company is never blocked). The domain limits an interview to 20 participants; the table does not.
CREATE TABLE interview_participant (
    interview_id uuid NOT NULL
        CONSTRAINT interview_participant_interview_fk REFERENCES interview (id) ON DELETE CASCADE,
    contact_id   uuid NOT NULL
        CONSTRAINT interview_participant_contact_fk REFERENCES contact (id) ON DELETE CASCADE,
    CONSTRAINT interview_participant_pk PRIMARY KEY (interview_id, contact_id)
);

-- A contact's interviews (spec §5, history of interactions) and the contact delete's cascade.
CREATE INDEX interview_participant_contact_idx ON interview_participant (contact_id);
