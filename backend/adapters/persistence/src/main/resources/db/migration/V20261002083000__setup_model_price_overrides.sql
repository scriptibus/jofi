-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The prices the user gives models of an OpenAI-compatible provider (spec §3.2, ADR-0043, ADR-0055, #142):
-- such endpoints have no list price, so without a row here their calls are metered with an unknown
-- cost. A row prices calls recorded after it was set; `ai_cost_entry` stays append-only and keeps what it
-- recorded. Prices are millionths of a US dollar per million tokens; 0 is allowed (a local model).
-- Deleted with the provider. That only OpenAI-compatible providers get rows is a domain rule (the use
-- case checks the provider's kind); a check here could not see it and must never be stricter anyway.
CREATE TABLE ai_model_price_override (
    provider_id              uuid        NOT NULL,
    model                    text        NOT NULL,
    input_micros_per_million  bigint      NOT NULL,
    output_micros_per_million bigint      NOT NULL,
    updated_at               timestamptz NOT NULL,
    CONSTRAINT ai_model_price_override_pk PRIMARY KEY (provider_id, model),
    CONSTRAINT ai_model_price_override_provider_fk FOREIGN KEY (provider_id)
        REFERENCES ai_provider_config (id) ON DELETE CASCADE,
    -- Mirrors ModelName and CapabilityInput.MAX_MODEL_NAME (a name that is not only white space, at most
    -- 200 characters); white space as the ASCII class, never `\s`.
    CONSTRAINT ai_model_price_override_model_valid CHECK (
        model ~ '[^ \t\n\r\f\v]' AND char_length(model) <= 200),
    -- Mirrors ModelPriceOverride.MAX_MICROS_PER_MILLION: 0 to 10,000 US dollars per million tokens.
    CONSTRAINT ai_model_price_override_input_valid CHECK (
        input_micros_per_million BETWEEN 0 AND 10000000000),
    CONSTRAINT ai_model_price_override_output_valid CHECK (
        output_micros_per_million BETWEEN 0 AND 10000000000)
);
