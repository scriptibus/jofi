-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- Single-user login (ADR-0017): the one account and the login sessions (#16).

-- The one Jofi user: at most one row (the primary key can only be TRUE). No user name; the password
-- is stored as an argon2id hash only (PHC string format with parameters and salt).
CREATE TABLE user_account (
    singleton           boolean     PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    password_hash       text        NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    created_at          timestamptz NOT NULL,
    password_changed_at timestamptz NOT NULL,
    CHECK (password_changed_at >= created_at)
);

-- Login sessions, managed by Spring Session JDBC (schema-postgresql.sql of spring-session-jdbc 4.1.1,
-- unchanged apart from lower-case names). Sessions survive restarts and are shared by app and worker.
-- Ephemeral bearer credentials: excluded from export/import (#26), a restore starts logged out.
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval int          NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
