-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The `companies` context's contact persons (spec §5, #74). Contacts are third-party personal data
-- (spec §13): deleting a contact deletes its channels, and deleting a company deletes its contacts
-- (ADR-0041). Check constraints mirror the domain (`ContactDetails`, `ContactChannel`, `Contact`) but are
-- never stricter and use no locale-dependent logic (ADR-0041): whitespace is ASCII `[ \t\n\r\f\v]`, and
-- the rules that need Unicode classes (whitespace in email addresses, digits in phone numbers, control
-- characters) stay in the domain. Every constraint is named, so repositories can map violations by name.

CREATE TABLE contact (
    id                 uuid        CONSTRAINT contact_pk PRIMARY KEY,
    -- Optional; the contact goes with its company.
    company_id         uuid        CONSTRAINT contact_company_fk REFERENCES company (id) ON DELETE CASCADE,
    name               text        NOT NULL CONSTRAINT contact_name_valid
        CHECK (name ~ '[^ \t\n\r\f\v]' AND name !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(name) <= 200),
    role               text        CONSTRAINT contact_role_valid
        CHECK (role ~ '[^ \t\n\r\f\v]' AND role !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(role) <= 200),
    -- Markdown written by the user.
    relationship_notes text        CONSTRAINT contact_relationship_notes_valid CHECK (
        relationship_notes ~ '[^ \t\n\r\f\v]' AND relationship_notes !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$'
        AND char_length(relationship_notes) <= 50000),
    -- Optimistic locking: every change increments it, and a change based on an older version is rejected.
    version            bigint      NOT NULL DEFAULT 0 CONSTRAINT contact_version_valid CHECK (version >= 0),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT contact_updated_after_created CHECK (updated_at >= created_at)
);

-- The contacts of a company: list filter, the company delete's count and its cascade.
CREATE INDEX contact_company_idx ON contact (company_id);

-- Fuzzy name search (spec §8.4); pg_trgm ignores case, so no lower() is needed.
CREATE INDEX contact_name_trgm_idx ON contact USING gin (name gin_trgm_ops);

-- Ways to reach a contact, in the user's order (`position` from 0, so at most 20 per contact). Exact
-- duplicates are the domain's job: a unique index on values up to 2048 characters could exceed the index
-- row size, which would make the database stricter than the domain.
CREATE TABLE contact_channel (
    contact_id uuid     NOT NULL CONSTRAINT contact_channel_contact_fk REFERENCES contact (id) ON DELETE CASCADE,
    position   smallint NOT NULL CONSTRAINT contact_channel_position_valid CHECK (position BETWEEN 0 AND 19),
    kind       text     NOT NULL CONSTRAINT contact_channel_kind_valid
        CHECK (kind IN ('EMAIL', 'PHONE', 'WEB', 'OTHER')),
    value      text     NOT NULL CONSTRAINT contact_channel_value_valid
        CHECK (value ~ '[^ \t\n\r\f\v]' AND value !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(value) <= 2048),
    label      text     CONSTRAINT contact_channel_label_valid
        CHECK (label ~ '[^ \t\n\r\f\v]' AND label !~ '^[ \t\n\r\f\v]|[ \t\n\r\f\v]$' AND char_length(label) <= 100),
    CONSTRAINT contact_channel_pk PRIMARY KEY (contact_id, position),
    -- The last `@` has text on both sides.
    CONSTRAINT contact_channel_email_valid
        CHECK (kind <> 'EMAIL' OR (value ~ '.@[^@]+$' AND char_length(value) <= 320)),
    CONSTRAINT contact_channel_phone_valid CHECK (kind <> 'PHONE' OR char_length(value) <= 64),
    -- Absolute http(s) with a host and without user info, as `company.website`.
    CONSTRAINT contact_channel_web_valid
        CHECK (kind <> 'WEB' OR (value ~* '^https?://[^/?# \t\n\r\f\v]+' AND value !~ '^[^:]+://[^/?#]*@')),
    CONSTRAINT contact_channel_other_valid CHECK (kind <> 'OTHER' OR char_length(value) <= 500)
);
