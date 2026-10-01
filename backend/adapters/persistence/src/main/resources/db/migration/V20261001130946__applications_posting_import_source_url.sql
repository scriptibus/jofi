-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Importing a posting from a URL (spec §8.1, #97): `source_url` is the link, already normalised (tracking
-- parameters stripped) before it is stored, so the same link matches on a later import. The check mirrors
-- `application_source.original_url` and `WebAddress`, never stricter (ADR-0041). It is kept for the lifetime of
-- the import, including a succeeded one (unlike `description`), so a double-submit lookup and the pending-import
-- changelog always know which link an import is for.
ALTER TABLE posting_import
    ADD COLUMN source_url text CONSTRAINT posting_import_source_url_valid CHECK (
        source_url ~* '^https?://[^/?# \t\n\r\f\v]+' AND source_url !~ '^[^:]+://[^/?#]*@'
        AND char_length(source_url) <= 2048);

-- "Is this link already pending or imported?" (#97, #187 finding F6), as `application_source_original_url_idx`.
CREATE INDEX posting_import_source_url_idx ON posting_import USING hash (source_url);
