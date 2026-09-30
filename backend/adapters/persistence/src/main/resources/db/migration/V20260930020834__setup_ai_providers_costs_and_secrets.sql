-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Encrypted secrets (shared kernel, SecretStorePort; ADR-0017) and the `setup` context's AI
-- configuration: providers, per-task model assignments, model capabilities, cost meter and monthly
-- budget (spec §3.2, ADR-0032). Enum-like text columns list the domain enum names; renaming one
-- needs a migration.

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
    -- One key per provider: deleting or rotating one provider's key never affects another. A secret
    -- still referenced by a provider cannot be deleted (default NO ACTION).
    api_key_secret_id uuid UNIQUE REFERENCES secret (id),
    -- Credentials belong in `secret`: no user info, query or fragment in the URL.
    base_url          text CHECK (
        base_url ~* '^https?://[^/?#\s]+'
        AND base_url !~ '^[^:]+://[^/?#]*@'
        AND base_url !~ '[?#]'),
    -- Mirrors ProviderKind: cloud providers have fixed endpoints and need a key; an
    -- OpenAI-compatible endpoint needs a base URL and may run without a key.
    CONSTRAINT ai_provider_config_base_url_matches_kind
        CHECK ((kind = 'OPENAI_COMPATIBLE') = (base_url IS NOT NULL)),
    CONSTRAINT ai_provider_config_api_key_matches_kind
        CHECK (kind = 'OPENAI_COMPATIBLE' OR api_key_secret_id IS NOT NULL)
);

-- What a provider's model can do (#19 fills it, the user may correct it). Capabilities belong to the
-- provider and model, not to a task assignment. Deleted with its provider.
CREATE TABLE ai_model_capability (
    provider_id           uuid        NOT NULL REFERENCES ai_provider_config (id) ON DELETE CASCADE,
    model                 text        NOT NULL CHECK (model ~ '\S'),
    -- Feature capabilities (CapabilityName); the context size has its own column.
    capabilities          text[]      NOT NULL DEFAULT '{}' CHECK (
        array_position(capabilities, NULL) IS NULL
        AND capabilities <@ ARRAY['TOOL_USE', 'STREAMING', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'EMBEDDING']),
    context_window_tokens integer     CHECK (context_window_tokens > 0),
    source                text        NOT NULL CHECK (source IN ('USER', 'DETECTED')),
    updated_at            timestamptz NOT NULL,
    PRIMARY KEY (provider_id, model)
);

-- At most one model per AI task. A provider with assigned tasks cannot be deleted.
CREATE TABLE ai_model_assignment (
    task        text PRIMARY KEY CHECK (task IN (
        'SCANNER_PRE_SCORING', 'CLASSIFICATION', 'LANGUAGE_TONE_DETECTION', 'EXTRACTION',
        'KNOWLEDGE_INTERVIEW', 'DOCUMENT_GENERATION', 'INTERVIEW_TRAINING', 'CHAT', 'EMBEDDING',
        'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH')),
    provider_id uuid NOT NULL REFERENCES ai_provider_config (id),
    model       text NOT NULL CHECK (model ~ '\S')
);

CREATE INDEX ai_model_assignment_provider_idx ON ai_model_assignment (provider_id);

-- Append-only AI cost meter. No foreign key to the provider on purpose: the history outlives a
-- deleted provider config, so the provider kind is kept as a snapshot. Costs are integer micros
-- (millionths) of the accounting currency, USD (ADR-0032).
CREATE TABLE ai_cost_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task          text        NOT NULL CHECK (task IN (
        'SCANNER_PRE_SCORING', 'CLASSIFICATION', 'LANGUAGE_TONE_DETECTION', 'EXTRACTION',
        'KNOWLEDGE_INTERVIEW', 'DOCUMENT_GENERATION', 'INTERVIEW_TRAINING', 'CHAT', 'EMBEDDING',
        'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH')),
    provider_id   uuid        NOT NULL,
    provider_kind text        NOT NULL
        CHECK (provider_kind IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'MISTRAL', 'OPENAI_COMPATIBLE')),
    model         text        NOT NULL CHECK (model ~ '\S'),
    input_tokens  bigint      NOT NULL CHECK (input_tokens >= 0),
    output_tokens bigint      NOT NULL CHECK (output_tokens >= 0),
    cost_micros   bigint      NOT NULL CHECK (cost_micros >= 0),
    currency      text        NOT NULL CHECK (currency = 'USD'),
    occurred_at   timestamptz NOT NULL
);

CREATE INDEX ai_cost_entry_occurred_at_idx ON ai_cost_entry (occurred_at, id);

-- Like the changelog, the meter cannot be rewritten through the application; restores replace the
-- table with TRUNCATE, which these row triggers do not block.
CREATE FUNCTION reject_append_only_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER ai_cost_entry_append_only
    BEFORE UPDATE OR DELETE ON ai_cost_entry
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

-- The optional monthly budget cap in USD: at most one row (the primary key can only be TRUE).
CREATE TABLE ai_monthly_budget (
    singleton  boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    cap_micros bigint  NOT NULL CHECK (cap_micros > 0),
    currency   text    NOT NULL CHECK (currency = 'USD')
);
