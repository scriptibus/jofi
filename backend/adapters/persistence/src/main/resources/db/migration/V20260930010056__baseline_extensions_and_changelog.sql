-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Baseline: extensions every context builds on (ADR-0008) and the shared audit trail.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- One row per change (spec §13 Auditability). Append-only: see the trigger below.
-- Text checks mirror the domain's not-blank rules: `~ '\S'` needs at least one non-whitespace
-- character (NULL passes, so optional columns stay optional).
CREATE TABLE changelog_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type   text        NOT NULL CHECK (entity_type ~ '\S'),
    entity_id     text        NOT NULL CHECK (entity_id ~ '\S'),
    actor_kind    text        NOT NULL
        CHECK (actor_kind IN ('USER', 'AI', 'SCANNER', 'EXTERNAL_CLIENT', 'SYSTEM')),
    -- Scanner, external client and system actors carry a name; the user and the AI do not.
    actor_name    text        CHECK (actor_name ~ '\S'),
    occurred_at   timestamptz NOT NULL,
    description   text        NOT NULL CHECK (description ~ '\S'),
    -- JSON array of {"field", "before", "after"} objects.
    field_changes jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(field_changes) = 'array'),
    reason        text        CHECK (reason ~ '\S'),
    CONSTRAINT changelog_entry_actor_name_matches_kind
        CHECK ((actor_kind IN ('USER', 'AI')) = (actor_name IS NULL))
);

CREATE INDEX changelog_entry_entity_idx ON changelog_entry (entity_type, entity_id, occurred_at, id);
CREATE INDEX changelog_entry_recent_idx ON changelog_entry (occurred_at, id);

-- The audit trail must not be rewritten through the application. Restores (export/import) replace
-- the table wholesale with TRUNCATE, which these row triggers do not block.
CREATE FUNCTION changelog_entry_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'changelog_entry is append-only (% rejected)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER changelog_entry_append_only
    BEFORE UPDATE OR DELETE ON changelog_entry
    FOR EACH ROW EXECUTE FUNCTION changelog_entry_reject_change();
