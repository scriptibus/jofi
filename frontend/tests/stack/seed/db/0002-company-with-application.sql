-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- e2e seed: a company with one application (it cannot be deleted: 409 `has-applications`) and an
-- AI profile whose Markdown tries raw HTML, a javascript: link and a remote image, which the company
-- page must render inert. Browser projects only read this company; nothing changes it.
-- Idempotent: every run leaves the same rows; changelog entries are written only on first creation.
-- Seed the application through the API instead once it exists (#82): see frontend/AGENTS.md. The
-- profile has no API (the AI writes it, M3), so it stays here.

WITH company_row AS (
    INSERT INTO company (id, name, website, industry, size, locations, careers_page, research_notes,
                         profile, profile_generated_at, preference, preference_reason, created_at, updated_at)
    VALUES ('00000000-0000-4000-8000-0000000e2e10', 'Seeded Holdings (e2e)', 'https://seeded.example',
            'Logistics', 'LARGE', ARRAY['Hamburg', 'Remote'], 'https://seeded.example/careers',
            E'**Seeded** research notes with a [safe link](https://seeded.example/about).',
            E'## About\n\nSeeded profile text. <script>window.__seedXss = true</script>\n\n'
                || E'[Click me](javascript:window.__seedXss=true) and ![tracking pixel](https://tracker.invalid/p.gif)',
            '2026-09-30T10:00:00Z', 'FAVOURITE', 'Seeded for e2e', now(), now())
    ON CONFLICT (id) DO UPDATE
        SET name = EXCLUDED.name,
            website = EXCLUDED.website,
            industry = EXCLUDED.industry,
            size = EXCLUDED.size,
            locations = EXCLUDED.locations,
            careers_page = EXCLUDED.careers_page,
            research_notes = EXCLUDED.research_notes,
            profile = EXCLUDED.profile,
            profile_generated_at = EXCLUDED.profile_generated_at,
            preference = EXCLUDED.preference,
            preference_reason = EXCLUDED.preference_reason
    RETURNING id, (xmax = 0) AS created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'company', id::text, 'SYSTEM', 'e2e-seed', now(), 'Seeded a company with an application for e2e tests'
FROM company_row
WHERE created;

WITH application_row AS (
    INSERT INTO application (id, company_id, title, created_at, updated_at)
    VALUES ('00000000-0000-4000-8000-0000000e2e11', '00000000-0000-4000-8000-0000000e2e10',
            'Seeded Engineer (e2e)', now(), now())
    ON CONFLICT (id) DO UPDATE
        SET company_id = EXCLUDED.company_id,
            title = EXCLUDED.title
    RETURNING id, (xmax = 0) AS created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'application', id::text, 'SYSTEM', 'e2e-seed', now(), 'Seeded an application for e2e tests'
FROM application_row
WHERE created;
