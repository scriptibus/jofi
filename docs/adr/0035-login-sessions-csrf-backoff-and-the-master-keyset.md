<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0035: Login, sessions, CSRF, backoff and the master keyset

- Status: accepted
- Date: 2026-09-30
- Source: issue #16; refines ADR-0017 and ADR-0029; threat model T4, T5

## Context

ADR-0017 fixed the building blocks (Spring Security, one user, argon2id, a session cookie, Tink
AES-GCM with a master key in the data volume). Issue #16 needs the details: how the SPA logs in and
keeps CSRF protection, how guessing is slowed down, who may choose the first password when the
instance is reachable from a network, where the master key lives and how it can be rotated.

## Decision

### Login and sessions

- **One user, no user name.** `user_account` holds at most one row (argon2id hash, created and
  password-changed times). Every session's principal is `owner`.
- **Always log in.** Every `/api/**` request needs a session, also on localhost, except
  `GET /api/auth/session` (status for the login screen, hands out the CSRF cookie),
  `POST /api/auth/login` and `POST /api/auth/first-run`; `/actuator/health` stays open for the
  container healthcheck. `SecurityConfiguration.PUBLIC_API` lists them; a test walks every mapping.
- **REST endpoints, not form login** (`AuthController`, in the OpenAPI contract): the controller
  checks the password through a use case, then `SessionSecurity.startSession` changes the session id
  (session fixation), renews the CSRF token and stores the security context. Logout calls
  `request.logout()`, which runs Spring Security's logout handlers (session invalidated, CSRF cookie
  cleared); the logout filter itself is off.
- **Sessions in PostgreSQL** (Spring Session JDBC 4.1, tables from our Flyway migration), so they
  survive restarts; idle timeout `JOFI_SESSION_TIMEOUT`, default 7 days (a personal app on a phone).
  A password change ends every other session of the user.
- **Cookies:** `SESSION` is `HttpOnly`, `SameSite=Lax`, `Secure` when the request arrived over HTTPS.
  The serializer is configured in code because Spring Boot applies `server.servlet.session.cookie.*`
  only with an embedded server. `server.forward-headers-strategy=native` makes Tomcat trust
  `X-Forwarded-*` from private addresses, so Caddy or Tailscale in front mark the cookies `Secure`.

### CSRF

Spring Security 7's `csrf.spa()`: the token is in the readable `XSRF-TOKEN` cookie (`SameSite=Lax`),
the SPA echoes it in `X-XSRF-TOKEN` on every unsafe request (`frontend/src/api/fetcher.ts`), also on
login and first run (login CSRF). The token is renewed at login and cleared at logout, so the SPA
calls `GET /api/auth/session` again afterwards. Refusals are `403` problem details
(`urn:jofi:problem:system:csrf`).

### Password rules and hashing

- argon2id with the OWASP Password Storage Cheat Sheet parameters **m = 19 MiB, t = 2, p = 1**,
  16-byte salt, 32-byte hash (Spring Security's `Argon2PasswordEncoder`, Bouncy Castle). At most two
  hashes run at once, so parallel requests cannot exhaust memory.
- A new password needs **15 to 256 characters** (NIST SP 800-63B-4 for a single factor; the upper
  bound keeps hashing cheap to refuse). No composition rules.

### Backoff instead of lockout

Every password check (login, first run, password change) passes two counters, kept in memory:
per client address 5 free failures, then 1 s doubling up to 15 min; for all clients together 50 free
failures, then up to 1 min. An attempt counts as failed as soon as it is let through, so parallel
guesses cannot slip past, and a success resets both. A throttled attempt answers `429` with
`Retry-After` before any hashing. Failed attempts are logged without password or client address.
There is no permanent lockout: an attacker could otherwise lock the only user out.

### First run and exposure (ADR-0029)

The app binds to `127.0.0.1` outside the container (`JOFI_SERVER_ADDRESS`). Where it is reachable
from is `JOFI_BIND_ADDRESS` (the compose port binding, default `127.0.0.1`; the image defaults to
`0.0.0.0`, the safe assumption). If that is not a loopback address and no password exists yet,
startup writes a random one-time **setup token** (32 bytes, Base64url) to
`<data>/secrets/setup-token` and logs only its path; first run then needs it. After first run the
token file is removed and `POST /api/auth/first-run` answers `404`.

### Master keyset and secrets

- On the first start the app generates a Tink AES-256-GCM keyset and writes it as cleartext JSON to
  `<data>/secrets/master-keyset.json` (`JOFI_DATA_DIR`, `/data` in the image). The directory is
  `0700`, the file `0600`; looser permissions found at startup are tightened. `app` and `worker` may
  start together: the file is written under a temporary name and hard-linked into place, so one of
  them wins and the other reads it. It is generated in an `ApplicationRunner`, so the image's AOT
  training run never bakes a key into the image. Its content is never logged.
- `SecretRepository` (`SecretStorePort`) stores only ciphertext in `secret`, encrypted through
  `SecretCipherPort` with the associated data `jofi:secret:<id>`: a ciphertext moved to another row
  does not decrypt, and a tampered one is reported as `Undecryptable`.
- A database dump alone reveals no secret; the data volume and the database together do. Backups
  must hold both (#26 decides how the key is backed up).

### Key rotation

Tink keysets hold several keys and every ciphertext names the key it was made with. Rotation is
therefore: add a new primary key to the keyset (older keys stay for decryption), re-encrypt every
row of `secret` with `SecretStorePort.put`, then remove the old key. This is not automated yet; it
needs a maintenance task (follow-up issue), and until then a compromised keyset means: delete it,
restart (a new one is generated) and re-enter the API keys.

### Transactions

A mutation and its changelog entry are written together through `TransactionPort` (Spring's JDBC
transaction, which jOOQ joins); the caller commits only an accepted result, so a failed changelog
append rolls the mutation back.

Docs consulted: Spring Security 7.1 reference (CSRF for SPAs, session management, servlet API
integration), Spring Boot 4.1 reference (Spring Session), Tink Java docs (AEAD, keyset formats),
OWASP Password Storage Cheat Sheet, NIST SP 800-63B-4.

## Consequences

- The login UI (#22) calls `GET /api/auth/session` first, then first run or login, and must send the
  CSRF header (the generated client does).
- MCP client tokens (#46) will need their own authentication path next to the session.
- Export/import (#26) covers `user_account` and `secret`; `spring_session*` are ephemeral and are
  excluded (a restore starts logged out). The master keyset must travel with a backup of `secret`.
- In-memory backoff counts reset on restart; an attacker cannot restart the app, so this is accepted.
- Spring Session 4.1.1's poms name a wrong licence (spring-session#3910); the licence gate allows
  exactly that version with a reason, so the next version is checked again.
