-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Posting imports (spec §8.1, #96): a pasted job posting on its way to a DISCOVERED application. Checks mirror
-- `PostingImport` and are never stricter (ADR-0041): whitespace is ASCII `[ \t\n\r\f\v]`, never `\s`. Every constraint
-- is named.
--
-- `description` is the pasted text in the stored form of a job description (as
-- `application_description_snapshot.description`): untrusted data, never logged. It is kept while the import is pending
-- or failed, so a failed import can be retried, and set to NULL when the import succeeds, since the application's
-- discovery snapshot holds it from then on (deleting the application leaves no copy here). `application_id` is the
-- application the import created, without a foreign key: the import stays a record of what happened after the
-- application is deleted. `attempt` counts the runs asked for; a job run stores its outcome only for its own attempt.
CREATE TABLE posting_import (
    id             uuid        CONSTRAINT posting_import_pk PRIMARY KEY,
    description    text        CONSTRAINT posting_import_description_valid CHECK (
        description ~ '[^ \t\n\r\f\v]' AND description !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(description) <= 100000),
    status         text        NOT NULL CONSTRAINT posting_import_status_valid
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure        text        CONSTRAINT posting_import_failure_valid CHECK (failure IN (
        'AI_NOT_CONFIGURED', 'AI_AUTHENTICATION_FAILED', 'AI_UNAVAILABLE', 'AI_REJECTED', 'UNREADABLE_ANSWER',
        'NOT_A_POSTING', 'NOT_QUEUED', 'NOT_COMPLETED')),
    application_id uuid,
    attempt        integer     NOT NULL CONSTRAINT posting_import_attempt_valid CHECK (attempt >= 1),
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,

    CONSTRAINT posting_import_failure_matches_status CHECK ((status = 'FAILED') = (failure IS NOT NULL)),
    CONSTRAINT posting_import_application_matches_status
        CHECK ((status = 'SUCCEEDED') = (application_id IS NOT NULL)),
    CONSTRAINT posting_import_description_matches_status CHECK ((status = 'SUCCEEDED') = (description IS NULL)),
    CONSTRAINT posting_import_updated_after_created CHECK (updated_at >= created_at)
);
