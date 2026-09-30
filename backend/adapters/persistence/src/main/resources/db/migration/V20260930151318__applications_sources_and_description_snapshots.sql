-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Where applications were found and the job description history (spec §6.1, §8.4, #78, ADR-0046). Checks
-- mirror `ApplicationSource`, `WebAddress`, `DescriptionText`, `ContentHash` and `DescriptionSnapshot` and are
-- never stricter (ADR-0041): whitespace is ASCII `[ \t\n\r\f\v]`, never `\s`; the case-insensitive scheme
-- match covers ASCII letters only. Every constraint is named. Posting text is untrusted data, stored as found.

-- One place a job was found. The link is stored, never fetched here. A source goes with its application.
CREATE TABLE application_source (
    id             uuid        CONSTRAINT application_source_pk PRIMARY KEY,
    application_id uuid        NOT NULL
        CONSTRAINT application_source_application_fk REFERENCES application (id) ON DELETE CASCADE,
    kind           text        NOT NULL CONSTRAINT application_source_kind_valid
        CHECK (kind IN ('SCANNER', 'URL', 'MANUAL_CHAT')),
    -- As `company.website`: absolute http(s) with a host and without user info (IDN hosts are the domain's job).
    original_url   text        CONSTRAINT application_source_original_url_valid CHECK (
        original_url ~* '^https?://[^/?# \t\n\r\f\v]+' AND original_url !~ '^[^:]+://[^/?#]*@'
        AND char_length(original_url) <= 2048),
    discovered_at  timestamptz NOT NULL,
    -- When the posting was found gone; NULL while online. Its snapshots stay (spec §8.4).
    offline_since  timestamptz,

    CONSTRAINT application_source_url_matches_kind CHECK (kind <> 'URL' OR original_url IS NOT NULL),
    CONSTRAINT application_source_offline_after_discovery CHECK (offline_since >= discovered_at)
);

-- The sources of an application, oldest first (loaded with the application).
CREATE INDEX application_source_application_idx ON application_source (application_id, discovered_at);

-- "Was this link imported already?" (#97). A hash index, since links of up to 2048 characters could exceed
-- a btree's row size limit.
CREATE INDEX application_source_original_url_idx ON application_source USING hash (original_url);

-- One version of a source's job description, the full text, stored so it survives the posting going
-- offline. `content_hash` is SHA-256 of the text's UTF-8 bytes in lower-case hex, as the domain computes it
-- (`ContentHash`), so a stored hash always matches its text. `frozen_at` is set once, when the application
-- was applied to.
CREATE TABLE application_description_snapshot (
    id           uuid        CONSTRAINT application_description_snapshot_pk PRIMARY KEY,
    source_id    uuid        NOT NULL
        CONSTRAINT application_description_snapshot_source_fk REFERENCES application_source (id) ON DELETE CASCADE,
    description  text        NOT NULL CONSTRAINT application_description_snapshot_description_valid CHECK (
        description ~ '[^ \t\n\r\f\v]' AND description !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(description) <= 100000),
    content_hash text        NOT NULL CONSTRAINT application_description_snapshot_content_hash_matches
        CHECK (content_hash = encode(sha256(convert_to(description, 'UTF8')), 'hex')),
    reason       text        NOT NULL CONSTRAINT application_description_snapshot_reason_valid
        CHECK (reason IN ('DISCOVERY', 'CHANGE_DETECTED', 'MANUAL')),
    captured_at  timestamptz NOT NULL,
    frozen_at    timestamptz,

    CONSTRAINT application_description_snapshot_frozen_after_capture CHECK (frozen_at >= captured_at)
);

-- The versions of a source in order, and its newest one.
CREATE INDEX application_description_snapshot_source_idx
    ON application_description_snapshot (source_id, captured_at, id);

-- A snapshot never changes: the only update allowed is freezing it once, which changes nothing else. Deletes
-- come only with the source (cascade); restores replace the table with TRUNCATE, which this row trigger does
-- not see.
CREATE FUNCTION application_description_snapshot_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.frozen_at IS NULL AND NEW.frozen_at IS NOT NULL
        AND (NEW.id, NEW.source_id, NEW.description, NEW.content_hash, NEW.reason, NEW.captured_at)
            IS NOT DISTINCT FROM (OLD.id, OLD.source_id, OLD.description, OLD.content_hash, OLD.reason, OLD.captured_at)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'update of "application_description_snapshot" violates "application_description_snapshot_immutable"'
        USING DETAIL = 'A description snapshot never changes; it can only be frozen, once.',
            ERRCODE = 'restrict_violation', CONSTRAINT = 'application_description_snapshot_immutable';
END;
$$;

CREATE TRIGGER application_description_snapshot_immutable
    BEFORE UPDATE ON application_description_snapshot
    FOR EACH ROW EXECUTE FUNCTION application_description_snapshot_reject_change();
