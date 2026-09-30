-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- e2e seed: the fake AI provider (service `fake-ai`) as an OPENAI_COMPATIBLE provider, one model per
-- AI task (`fake-<task>`, which selects the fixture directory), and each model's capabilities.
-- Idempotent: every run leaves the same rows; the changelog entry is written only when the provider is
-- first created. Applied by the `seed` service in one transaction, after Flyway has migrated.
-- Seed through the API instead as soon as one exists for this data (#23): see frontend/AGENTS.md.

WITH provider AS (
    INSERT INTO ai_provider_config (id, display_name, kind, api_key_secret_id, base_url)
    VALUES ('00000000-0000-4000-8000-0000000e2e01', 'Fake AI (e2e)', 'OPENAI_COMPATIBLE', NULL,
            'http://fake-ai:8080/v1')
    ON CONFLICT (id) DO UPDATE
        SET display_name = EXCLUDED.display_name,
            kind = EXCLUDED.kind,
            api_key_secret_id = NULL,
            base_url = EXCLUDED.base_url
    RETURNING id, (xmax = 0) AS created
)
INSERT INTO changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description)
SELECT 'ai-provider', id::text, 'SYSTEM', 'e2e-seed', now(), 'Seeded the fake AI provider for e2e tests'
FROM provider
WHERE created;

-- Voice tasks (SPEECH_TO_TEXT, TEXT_TO_SPEECH) get models with the voice work (M5).
INSERT INTO ai_model_assignment (task, provider_id, model)
SELECT task, '00000000-0000-4000-8000-0000000e2e01', 'fake-' || replace(lower(task), '_', '-')
FROM unnest(ARRAY[
    'SCANNER_PRE_SCORING', 'CLASSIFICATION', 'LANGUAGE_TONE_DETECTION', 'EXTRACTION',
    'KNOWLEDGE_INTERVIEW', 'DOCUMENT_GENERATION', 'INTERVIEW_TRAINING', 'CHAT', 'EMBEDDING']) AS task
ON CONFLICT (task) DO UPDATE
    SET provider_id = EXCLUDED.provider_id,
        model = EXCLUDED.model;

INSERT INTO ai_model_capability (provider_id, model, capabilities, context_window_tokens, source, updated_at)
SELECT provider_id,
       model,
       CASE WHEN task = 'EMBEDDING' THEN ARRAY['EMBEDDING'] ELSE ARRAY['TOOL_USE', 'STREAMING'] END,
       128000,
       'USER',
       now()
FROM ai_model_assignment
WHERE provider_id = '00000000-0000-4000-8000-0000000e2e01'
ON CONFLICT (provider_id, model) DO UPDATE
    SET capabilities = EXCLUDED.capabilities,
        context_window_tokens = EXCLUDED.context_window_tokens,
        source = EXCLUDED.source,
        updated_at = EXCLUDED.updated_at;
