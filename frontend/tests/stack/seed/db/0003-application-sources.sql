-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- e2e seed: two sources of the seeded application (0002), for the detail page's Sources card: a link the
-- user added (online, with a query string) and a scanner find that went offline. Browser projects only read
-- them. Idempotent: fixed ids, every run leaves the same rows; the changelog entry is written only on first
-- creation. Move this to the API once `POST /api/applications/{id}/sources` is implemented.

WITH source_rows AS (
    INSERT INTO application_source (id, application_id, kind, original_url, discovered_at, offline_since)
    VALUES ('00000000-0000-4000-8000-0000000e2e12', '00000000-0000-4000-8000-0000000e2e11', 'URL',
            'https://jobs.seeded.example/engineer?ref=e2e&team=platform', '2026-09-01T08:00:00Z', NULL),
           ('00000000-0000-4000-8000-0000000e2e13', '00000000-0000-4000-8000-0000000e2e11', 'SCANNER',
            'https://board.seeded.example/postings/4711', '2026-09-02T08:00:00Z', '2026-09-20T08:00:00Z')
    ON CONFLICT (id) DO UPDATE
        SET application_id = EXCLUDED.application_id,
            kind = EXCLUDED.kind,
            original_url = EXCLUDED.original_url,
            discovered_at = EXCLUDED.discovered_at,
            offline_since = EXCLUDED.offline_since
    RETURNING id, (xmax = 0) AS created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'application', '00000000-0000-4000-8000-0000000e2e11', 'SYSTEM', 'e2e-seed', now(),
       'Seeded two sources of an application for e2e tests'
FROM source_rows
WHERE created
LIMIT 1;
