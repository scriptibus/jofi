-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- e2e seed: two sources of the seeded application (0002), for the detail page's Sources card: a link the
-- user added (online, with a query string) and a scanner find that went offline. Browser projects only read
-- them. Idempotent: fixed ids, every run leaves the same rows. No changelog entry of their own: they are
-- part of the seeded application, whose creation 0002 logs (the stack check counts one entry per entity).
-- Move this to the API once `POST /api/applications/{id}/sources` is implemented.

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
        offline_since = EXCLUDED.offline_since;
