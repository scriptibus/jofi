-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Saved views of the application list and the application settings (spec §6.1, §6.2, §6.3, #81, ADR-0050). Checks
-- mirror `SavedViewDetails` and `ApplicationSettings` and are never stricter (ADR-0041): whitespace is ASCII
-- `[ \t\n\r\f\v]`, never `\s`. Every constraint is named.

-- A view's filter is a JSON document in the format `filter_version` names (`SavedViewDocument`), written and read
-- only by the application: the database checks its shape, not its filters, so a filter rule may change without a
-- migration (the reader leaves out what today's rules refuse). Company and contact ids in it are not foreign keys:
-- after a delete they match nothing, and neither delete is blocked by or cascades to a view (ADR-0050).
CREATE TABLE saved_view (
    id             uuid        CONSTRAINT saved_view_pk PRIMARY KEY,
    name           text        NOT NULL CONSTRAINT saved_view_name_valid CHECK (
        name ~ '[^ \t\n\r\f\v]' AND name !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(name) <= 100),
    filter         jsonb       NOT NULL CONSTRAINT saved_view_filter_valid CHECK (jsonb_typeof(filter) = 'object'),
    filter_version integer     NOT NULL CONSTRAINT saved_view_filter_version_valid CHECK (filter_version >= 1),
    version        bigint      NOT NULL DEFAULT 0 CONSTRAINT saved_view_version_valid CHECK (version >= 0),
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,

    CONSTRAINT saved_view_updated_after_created CHECK (updated_at >= created_at),
    -- Exactly equal names only: the domain's rule (unique ignoring case) is stricter, and case folding is
    -- locale-dependent SQL.
    CONSTRAINT saved_view_name_unique UNIQUE (name)
);

-- The one set of application settings; no row means the defaults (14 weeks, 14 days), so a backup from before this
-- table restores to the defaults.
CREATE TABLE application_settings (
    singleton            boolean     NOT NULL DEFAULT TRUE CONSTRAINT application_settings_pk PRIMARY KEY
        CONSTRAINT application_settings_singleton CHECK (singleton),
    ghosted_after_weeks  integer     NOT NULL CONSTRAINT application_settings_ghosted_after_weeks_valid
        CHECK (ghosted_after_weeks BETWEEN 1 AND 52),
    follow_up_after_days integer     NOT NULL CONSTRAINT application_settings_follow_up_after_days_valid
        CHECK (follow_up_after_days BETWEEN 1 AND 90),
    version              bigint      NOT NULL CONSTRAINT application_settings_version_valid CHECK (version >= 0),
    updated_at           timestamptz NOT NULL
);
