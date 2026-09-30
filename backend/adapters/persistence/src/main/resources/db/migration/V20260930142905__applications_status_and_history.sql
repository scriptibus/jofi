-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The status pipeline of applications (spec §6.2, #77, ADR-0044): the current status on `application`
-- and the full history in `application_status_change`. Checks mirror `ApplicationStatus`, `StatusChange`
-- and `Application` and are never stricter (ADR-0041); the transition matrix itself is the domain's job.
-- Rows written before this migration have no status: they start as DISCOVERED, or DECLINED if they already
-- hold a decline reason (the domain allows a reason only while declined or rejected, and "the user decided
-- against it" is the reading that claims least), and each gets one history entry.

-- A constant default adds the column without rewriting the table.
ALTER TABLE application
    ADD COLUMN status text NOT NULL DEFAULT 'DISCOVERED' CONSTRAINT application_status_valid CHECK (status IN (
        'DISCOVERED', 'SHORTLISTED', 'PREPARING', 'APPLIED', 'INTERVIEWING', 'OFFER',
        'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'DECLINED', 'GHOSTED'));

UPDATE application SET status = 'DECLINED' WHERE decline_category IS NOT NULL;

-- One entry per status change, appended in order (the identity is the order). `from_status` is NULL only
-- for the first entry, the status the application started with. Deleted with the application.
CREATE TABLE application_status_change (
    id               bigint      GENERATED ALWAYS AS IDENTITY CONSTRAINT application_status_change_pk PRIMARY KEY,
    application_id   uuid        NOT NULL
        CONSTRAINT application_status_change_application_fk REFERENCES application (id) ON DELETE CASCADE,
    from_status      text        CONSTRAINT application_status_change_from_status_valid CHECK (from_status IN (
        'DISCOVERED', 'SHORTLISTED', 'PREPARING', 'APPLIED', 'INTERVIEWING', 'OFFER',
        'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'DECLINED', 'GHOSTED')),
    to_status        text        NOT NULL CONSTRAINT application_status_change_to_status_valid CHECK (to_status IN (
        'DISCOVERED', 'SHORTLISTED', 'PREPARING', 'APPLIED', 'INTERVIEWING', 'OFFER',
        'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'DECLINED', 'GHOSTED')),
    -- Why, in the user's words (Markdown); a DECLINED or REJECTED entry keeps the decline reason's text.
    reason           text        CONSTRAINT application_status_change_reason_valid CHECK (
        reason ~ '[^ \t\n\r\f\v]' AND reason !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(reason) <= 5000),
    decline_category text        CONSTRAINT application_status_change_decline_category_valid CHECK (
        decline_category IN ('SALARY', 'LOCATION', 'REMOTE_POLICY', 'ROLE', 'COMPANY', 'SKILLS', 'TIMING',
                             'OTHER_OFFER', 'POSITION_FILLED', 'NO_REASON_GIVEN', 'OTHER')),
    -- Like `changelog_entry`: scanner, external client and system actors carry a name.
    actor_kind       text        NOT NULL CONSTRAINT application_status_change_actor_kind_valid
        CHECK (actor_kind IN ('USER', 'AI', 'SCANNER', 'EXTERNAL_CLIENT', 'SYSTEM')),
    actor_name       text        CONSTRAINT application_status_change_actor_name_valid
        CHECK (actor_name ~ '[^ \t\n\r\f\v]'),
    changed_at       timestamptz NOT NULL,

    CONSTRAINT application_status_change_actor_name_matches_kind
        CHECK ((actor_kind IN ('USER', 'AI')) = (actor_name IS NULL)),
    CONSTRAINT application_status_change_decline_category_matches_status
        CHECK ((decline_category IS NOT NULL) = (to_status IN ('DECLINED', 'REJECTED')))
);

-- The history of one application, in order.
CREATE INDEX application_status_change_application_idx ON application_status_change (application_id, id);

-- Every existing application gets the entry of the status it starts with.
INSERT INTO application_status_change (application_id, from_status, to_status, reason, decline_category,
                                       actor_kind, actor_name, changed_at)
SELECT id, NULL, status, decline_reason, decline_category, 'SYSTEM', 'status-history-backfill', created_at
FROM application
ORDER BY created_at, id;

-- Status-dependent, so added NOT VALID and validated only now that every row has its status (ADR-0041).
ALTER TABLE application ADD CONSTRAINT application_decline_reason_matches_status
    CHECK ((decline_category IS NOT NULL) = (status IN ('DECLINED', 'REJECTED'))) NOT VALID;
ALTER TABLE application VALIDATE CONSTRAINT application_decline_reason_matches_status;
