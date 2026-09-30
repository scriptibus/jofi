-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Encrypted secrets (shared kernel, SecretStorePort; ADR-0017) and the `setup` context's AI
-- configuration: providers, per-task model assignments, cost meter and monthly budget (spec §3.2).
-- Enum-like text columns list the domain enum names; renaming one needs a migration.

-- Tink AES-GCM ciphertext (#16). The key never exists in clear text in the database; the secret id
-- is bound as associated data, so a ciphertext copied to another row does not decrypt.
CREATE TABLE secret (
    id         uuid        PRIMARY KEY,
    ciphertext bytea       NOT NULL CHECK (octet_length(ciphertext) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (updated_at >= created_at)
);

-- A configured AI provider. It references its key in `secret`; it never holds the key.
CREATE TABLE ai_provider_config (
    id                uuid PRIMARY KEY,
    display_name      text NOT NULL CHECK (display_name ~ '\S'),
    kind              text NOT NULL
        CHECK (kind IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'MISTRAL', 'OPENAI_COMPATIBLE')),
    -- A secret still referenced by a provider cannot be deleted (default NO ACTION).
    api_key_secret_id uuid REFERENCES secret (id),
    base_url          text CHECK (base_url ~* '^https?://[^/?#\s]+'),
    -- Mirrors ProviderKind: cloud providers have fixed endpoints and need a key; an
    -- OpenAI-compatible endpoint needs a base URL and may run without a key.
    CONSTRAINT ai_provider_config_base_url_matches_kind
        CHECK ((kind = 'OPENAI_COMPATIBLE') = (base_url IS NOT NULL)),
    CONSTRAINT ai_provider_config_api_key_matches_kind
        CHECK (kind = 'OPENAI_COMPATIBLE' OR api_key_secret_id IS NOT NULL)
);

-- At most one model per AI task. A provider with assigned tasks cannot be deleted.
CREATE TABLE ai_model_assignment (
    task                  text    PRIMARY KEY CHECK (task IN (
        'SCANNER_PRE_SCORING', 'CLASSIFICATION', 'LANGUAGE_TONE_DETECTION', 'EXTRACTION',
        'KNOWLEDGE_INTERVIEW', 'DOCUMENT_GENERATION', 'INTERVIEW_TRAINING', 'CHAT', 'EMBEDDING',
        'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH')),
    provider_id           uuid    NOT NULL REFERENCES ai_provider_config (id),
    model                 text    NOT NULL CHECK (model ~ '\S'),
    -- Feature capabilities of the model; the context size has its own column.
    capabilities          text[]  NOT NULL DEFAULT '{}' CHECK (
        array_position(capabilities, NULL) IS NULL
        AND capabilities <@ ARRAY['TOOL_USE', 'STREAMING', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'EMBEDDING']),
    context_window_tokens integer CHECK (context_window_tokens > 0)
);

CREATE INDEX ai_model_assignment_provider_idx ON ai_model_assignment (provider_id);

-- Append-only AI cost meter. No foreign key to the provider on purpose: the history outlives a
-- deleted provider config. Costs are integer micros (millionths) of `currency` (ISO 4217).
CREATE TABLE ai_cost_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task          text        NOT NULL CHECK (task IN (
        'SCANNER_PRE_SCORING', 'CLASSIFICATION', 'LANGUAGE_TONE_DETECTION', 'EXTRACTION',
        'KNOWLEDGE_INTERVIEW', 'DOCUMENT_GENERATION', 'INTERVIEW_TRAINING', 'CHAT', 'EMBEDDING',
        'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH')),
    provider_id   uuid        NOT NULL,
    model         text        NOT NULL CHECK (model ~ '\S'),
    input_tokens  bigint      NOT NULL CHECK (input_tokens >= 0),
    output_tokens bigint      NOT NULL CHECK (output_tokens >= 0),
    cost_micros   bigint      NOT NULL CHECK (cost_micros >= 0),
    currency      text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    occurred_at   timestamptz NOT NULL
);

CREATE INDEX ai_cost_entry_occurred_at_idx ON ai_cost_entry (occurred_at, id);

-- The optional monthly budget cap: at most one row (the primary key can only be TRUE).
CREATE TABLE ai_monthly_budget (
    singleton  boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    cap_micros bigint  NOT NULL CHECK (cap_micros > 0),
    currency   text    NOT NULL CHECK (currency ~ '^[A-Z]{3}$')
);
