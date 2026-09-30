-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The `companies` context's company (spec §5, #73). Check constraints mirror the domain invariants
-- (`CompanyDetails`, `WebAddress`, `CompanyPreference`, `CompanyProfile`, `Company`) and enum names, but
-- are never stricter than the domain and use no locale-dependent logic (ADR-0041): whatever the domain
-- accepts must be storable. Every constraint is named, so repositories can map violations by name.
-- Text is stored trimmed and not blank. Whitespace in these checks is ASCII whitespace only
-- (`[ \t\n\r\f\v]`), never `\s`, whose meaning for other characters depends on the locale; it is a
-- subset of what Kotlin's `trim()` removes, so the database stays at most as strict as the domain.

-- Location rules the database can check without a locale: at most 50 entries, each not null, not
-- blank, trimmed and at most 200 characters, and no exact duplicates. The domain also drops duplicates
-- that differ only in case; `lower()` depends on the database's locale, so SQL leaves that to it.
-- Never tighten this function with CREATE OR REPLACE (existing rows would not be checked again); add a
-- new function and constraint with NOT VALID + VALIDATE instead (adapters/persistence/AGENTS.md).
CREATE FUNCTION company_locations_are_valid(locations text[]) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS
$$
SELECT cardinality(locations) <= 50
    AND NOT EXISTS (
        SELECT 1 FROM unnest(locations) AS location
        WHERE location IS NULL OR location !~ '[^ \t\n\r\f\v]' OR location ~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
            OR char_length(location) > 200)
    AND cardinality(locations) = (SELECT count(DISTINCT location) FROM unnest(locations) AS location)
$$;

CREATE TABLE company (
    id                   uuid        CONSTRAINT company_pk PRIMARY KEY,
    name                 text        NOT NULL CONSTRAINT company_name_valid
        CHECK (name ~ '[^ \t\n\r\f\v]' AND name !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
            AND char_length(name) <= 200),
    -- Web addresses: absolute http(s) with a host and without user info (credentials never belong in a
    -- link). Hosts may be internationalised or contain underscores, as the domain allows.
    website              text        CONSTRAINT company_website_valid CHECK (
        website ~* '^https?://[^/?# \t\n\r\f\v]+' AND website !~ '^[^:]+://[^/?#]*@'
        AND char_length(website) <= 2048),
    industry             text        CONSTRAINT company_industry_valid
        CHECK (industry ~ '[^ \t\n\r\f\v]' AND industry !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
            AND char_length(industry) <= 200),
    size                 text        CONSTRAINT company_size_valid
        CHECK (size IN ('MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'ENTERPRISE')),
    -- Ordered, the first is the main location.
    locations            text[]      NOT NULL DEFAULT '{}'
        CONSTRAINT company_locations_valid CHECK (company_locations_are_valid(locations)),
    careers_page         text        CONSTRAINT company_careers_page_valid CHECK (
        careers_page ~* '^https?://[^/?# \t\n\r\f\v]+' AND careers_page !~ '^[^:]+://[^/?#]*@'
        AND char_length(careers_page) <= 2048),
    -- Markdown written by the user.
    research_notes       text        CONSTRAINT company_research_notes_valid CHECK (
        research_notes ~ '[^ \t\n\r\f\v]' AND research_notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(research_notes) <= 50000),
    -- The AI-generated profile (Markdown) and when it was generated: both or neither.
    profile              text        CONSTRAINT company_profile_valid
        CHECK (profile ~ '[^ \t\n\r\f\v]' AND char_length(profile) <= 50000),
    profile_generated_at timestamptz,
    preference           text        NOT NULL DEFAULT 'NONE' CONSTRAINT company_preference_valid
        CHECK (preference IN ('NONE', 'FAVOURITE', 'BLACKLISTED')),
    preference_reason    text        CONSTRAINT company_preference_reason_valid CHECK (
        preference_reason ~ '[^ \t\n\r\f\v]' AND preference_reason !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(preference_reason) <= 1000),
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version              bigint      NOT NULL DEFAULT 0 CONSTRAINT company_version_valid CHECK (version >= 0),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT company_profile_has_generation_time
        CHECK ((profile IS NULL) = (profile_generated_at IS NULL)),
    CONSTRAINT company_reason_needs_preference
        CHECK (preference <> 'NONE' OR preference_reason IS NULL),
    CONSTRAINT company_updated_after_created
        CHECK (updated_at >= created_at)
);

-- Fuzzy name search and duplicate detection (spec §8.4): similarity (`%`, `similarity()`) and ILIKE.
-- pg_trgm ignores case, so no lower() is needed.
CREATE INDEX company_name_trgm_idx ON company USING gin (name gin_trgm_ops);

-- Scanners and knockouts look up the few flagged companies.
CREATE INDEX company_preference_idx ON company (preference) WHERE preference <> 'NONE';
