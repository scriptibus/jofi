-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The `companies` context's company (spec §5, #73). Check constraints mirror the domain invariants
-- (`Company`, `CompanyDetails`, `CompanyPreference`) and enum names; renaming one needs a migration.
-- Text is stored trimmed; `~ '\S'` needs at least one non-whitespace character.
CREATE TABLE company (
    id                   uuid        PRIMARY KEY,
    name                 text        NOT NULL
        CHECK (name ~ '\S' AND name = btrim(name) AND char_length(name) <= 200),
    -- Web addresses: absolute http(s) with a host and without user info (credentials never belong in a link).
    website              text        CHECK (
        website ~* '^https?://[^/?#\s]+' AND website !~ '^[^:]+://[^/?#]*@' AND char_length(website) <= 2048),
    industry             text
        CHECK (industry ~ '\S' AND industry = btrim(industry) AND char_length(industry) <= 200),
    size                 text        CHECK (size IN ('MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'ENTERPRISE')),
    -- Ordered, the first is the main location; blank and duplicate entries are rejected by the domain.
    locations            text[]      NOT NULL DEFAULT '{}'
        CHECK (array_position(locations, NULL) IS NULL AND cardinality(locations) <= 50),
    careers_page         text        CHECK (
        careers_page ~* '^https?://[^/?#\s]+' AND careers_page !~ '^[^:]+://[^/?#]*@'
        AND char_length(careers_page) <= 2048),
    -- Markdown written by the user.
    research_notes       text        CHECK (research_notes ~ '\S' AND char_length(research_notes) <= 50000),
    -- The AI-generated profile (Markdown) and when it was generated: both or neither.
    profile              text        CHECK (profile ~ '\S' AND char_length(profile) <= 50000),
    profile_generated_at timestamptz,
    preference           text        NOT NULL DEFAULT 'NONE'
        CHECK (preference IN ('NONE', 'FAVOURITE', 'BLACKLISTED')),
    preference_reason    text        CHECK (
        preference_reason ~ '\S' AND preference_reason = btrim(preference_reason)
        AND char_length(preference_reason) <= 1000),
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version              bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT company_profile_has_generation_time
        CHECK ((profile IS NULL) = (profile_generated_at IS NULL)),
    CONSTRAINT company_reason_needs_preference
        CHECK (preference <> 'NONE' OR preference_reason IS NULL),
    CHECK (updated_at >= created_at)
);

-- Fuzzy name search and duplicate detection (spec §8.4): similarity (`%`, `similarity()`) and ILIKE.
-- pg_trgm ignores case, so no lower() is needed.
CREATE INDEX company_name_trgm_idx ON company USING gin (name gin_trgm_ops);

-- Scanners and knockouts look up the few flagged companies.
CREATE INDEX company_preference_idx ON company (preference) WHERE preference <> 'NONE';
