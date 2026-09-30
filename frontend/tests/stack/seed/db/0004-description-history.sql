-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- e2e seed: applications for the Description tab's tests (tests/e2e/application-description.spec.ts), which
-- record texts and move the application to Applied (freezing a version). Sources cannot be added through the
-- API yet (501 until #96), so they are seeded here: one application per browser project, language and attempt
-- (a retry must start fresh, the freeze happens once), each with a link (online) and a scanner find (offline).
-- Ids: 00000000-0000-4000-8000-00e2e105PLAK with P = project (1 desktop-light, 2 desktop-dark, 3 phone),
-- L = language (1 English, 2 German), A = attempt (0, 1), K = 0 application, 1 link, 2 scanner find;
-- the company is ...00e2e1050000. Idempotent: fixed ids; changelog entries and the first status history
-- entry only when a row is first created. Move to the API once `POST /api/applications/{id}/sources` exists.

WITH company_row AS (
    INSERT INTO company (id, name, created_at, updated_at)
    VALUES ('00000000-0000-4000-8000-00e2e1050000', 'Description History (e2e)', now(), now())
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
    RETURNING id, (xmax = 0) AS created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'company', id::text, 'SYSTEM', 'e2e-seed', now(), 'Seeded a company for the description e2e tests'
FROM company_row
WHERE created;

CREATE TEMPORARY TABLE description_slot ON COMMIT DROP AS
SELECT format('00000000-0000-4000-8000-00e2e105%s%s%s', project.digit, language.digit, attempt.digit) AS prefix,
       format('Description history (e2e) %s %s %s', project.name, language.code, attempt.digit) AS title
FROM (VALUES ('1', 'desktop-light'), ('2', 'desktop-dark'), ('3', 'phone')) AS project (digit, name)
         CROSS JOIN (VALUES ('1', 'en'), ('2', 'de')) AS language (digit, code)
         CROSS JOIN (VALUES ('0'), ('1')) AS attempt (digit);

WITH application_row AS (
    INSERT INTO application (id, company_id, title, created_at, updated_at)
    SELECT (prefix || '0')::uuid, '00000000-0000-4000-8000-00e2e1050000', title, now(), now()
    FROM description_slot
    ON CONFLICT (id) DO UPDATE
        SET company_id = EXCLUDED.company_id,
            title = EXCLUDED.title
    RETURNING id, (xmax = 0) AS created
), history AS (
    INSERT INTO application_status_change (application_id, from_status, to_status, actor_kind, actor_name, changed_at)
    SELECT id, NULL, 'DISCOVERED', 'SYSTEM', 'e2e-seed', now()
    FROM application_row
    WHERE created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'application', id::text, 'SYSTEM', 'e2e-seed', now(), 'Seeded an application for the description e2e tests'
FROM application_row
WHERE created;

INSERT INTO application_source (id, application_id, kind, original_url, discovered_at, offline_since)
SELECT (prefix || '1')::uuid, (prefix || '0')::uuid, 'URL', 'https://jobs.history.example/platform-engineer',
       '2026-09-01T08:00:00Z'::timestamptz, NULL::timestamptz
FROM description_slot
UNION ALL
SELECT (prefix || '2')::uuid, (prefix || '0')::uuid, 'SCANNER', 'https://board.history.example/postings/105',
       '2026-09-02T08:00:00Z'::timestamptz, '2026-09-20T08:00:00Z'::timestamptz
FROM description_slot
ON CONFLICT (id) DO UPDATE
    SET application_id = EXCLUDED.application_id,
        kind = EXCLUDED.kind,
        original_url = EXCLUDED.original_url,
        discovered_at = EXCLUDED.discovered_at,
        offline_since = EXCLUDED.offline_since;
