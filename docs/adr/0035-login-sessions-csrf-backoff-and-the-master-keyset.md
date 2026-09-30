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
keeps CSRF protection, how guessing is slowed down, who may choose the first password, how a
forgotten password is recovered, where the master key lives, what happens when it goes missing and
how it can be rotated.

## Decision

### Login and sessions

- **One user, no user name.** `user_account` holds at most one row (argon2id hash, created and
  password-changed times, and an `account_id` drawn anew at every first run). Every session's
  principal is `owner`, and every session records the `account_id` it was started for.
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
  survive restarts; idle timeout `JOFI_SESSION_TIMEOUT`, default 7 days (a personal app on a phone),
  and an absolute lifetime since login `JOFI_SESSION_MAX_AGE`, default 30 days, however active the
  session is. `SessionValidityFilter` checks both on every request with a session, together with the
  `account_id`: a session of a deleted or reset account (or one restored from a backup) ends on its
  next request, and it fails closed when the account cannot be read.
  A password change ends every other session of the user; if that fails, the change is reported
  as `500 other-sessions-remain`, never as success.
- **Cookies:** `SESSION` is `HttpOnly`, `SameSite=Lax`, `Secure` when the request arrived over HTTPS.
  The serializer is configured in code because Spring Boot applies `server.servlet.session.cookie.*`
  only with an embedded server. `server.forward-headers-strategy=native` lets a TLS proxy in front
  (Caddy, Tailscale serve) mark requests as HTTPS, but only proxies listed in `JOFI_TRUSTED_PROXIES`
  (CIDRs, default loopback only) are believed: Tomcat's default trusts every private range, so any LAN
  client could fake its address (and dodge the per-client backoff) or fake HTTPS.

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
per client 5 free failures, then 1 s doubling up to 15 min; for all clients together 50 free
failures, then up to 1 min. The client's own counter is asked first, and only attempts it lets
through are charged to the global one: a single throttled client can therefore never keep the
global backoff armed and lock the owner out. An attempt counts as failed as soon as it is let
through, so parallel guesses cannot slip past, and a success resets both. A throttled attempt
answers `429` with `Retry-After` before any hashing. Failed attempts are logged without password or
client address. There is no permanent lockout: an attacker could otherwise lock the only user out.

A client is its address as the server sees it; IPv6 clients count by their /64 network, since one
host usually owns a whole /64. **Limitation:** when the server cannot see real client addresses, all
clients share one per-client key. That is the case behind rootless Podman (slirp4netns/pasta) and
behind the default Docker userland proxy, where every connection comes from the gateway, and behind a
proxy that is not listed in `JOFI_TRUSTED_PROXIES`. Then anyone reaching the port can put that
shared key into backoff and make the owner wait up to 15 minutes per attempt, though never lock
them out for good and never speed up guessing. Mitigation: put a TLS proxy (Caddy, Tailscale serve)
in front and list it in `JOFI_TRUSTED_PROXIES`, so `X-Forwarded-For` names the real client.

### First run always needs the setup token

While no password exists, startup writes a random one-time **setup token** (32 bytes, Base64url) to
`<data>/secrets/setup-token` (owner-only) and logs how to read it
(`docker compose exec app cat /data/secrets/setup-token`), never the token itself. First run always
needs it, also on localhost: a proxy or `tailscale serve` in front of `127.0.0.1`, or DNS rebinding
against a browser on the same machine, would otherwise let a stranger claim a fresh instance. After
first run the token file is removed and `POST /api/auth/first-run` answers `404`. The app binds to
`127.0.0.1` outside the container (`JOFI_SERVER_ADDRESS`); in the container, how far it is reachable
is the compose port binding `JOFI_BIND_ADDRESS` (ADR-0029).

### Password recovery

Starting with `JOFI_RESET_PASSWORD=true` deletes the account (not the data), ends every session,
records the reset in the changelog (`Actor.System("password-reset")`) and issues a new setup token:
whoever controls the server can start over, nobody else can. Sessions the reset could not delete
still end, because they belong to the old `account_id`. The flag has to be removed afterwards; the
log says so on every reset.

### Master keyset and secrets

- On the first start the app generates a Tink AES-256-GCM keyset and writes it as cleartext JSON to
  `<data>/secrets/master-keyset.json`. `JOFI_DATA_DIR` has no default and must be absolute (`/data`
  in the image), so a working directory never decides where keys go. The directory is `0700`, the
  file `0600`; looser permissions found at startup are tightened. `app` and `worker` may start
  together: the file is written under a random temporary name and hard-linked into place, so one of
  them wins and the other reads it. The data volume must therefore support hard links (local disks,
  Docker/Podman volumes); an SMB/CIFS share without them is refused with a clear message. The keyset
  is generated in an `ApplicationRunner`, so the image's AOT training run never bakes a key into the
  image. Its content is never logged.
- **A lost keyset never silently becomes a new one.** When a keyset is created, a check value (a
  Tink ciphertext of a fixed text) is recorded in `master_key_check`. At every start
  `VerifyMasterKeyUseCase` compares: if the database has a record but the file is missing or cannot
  decrypt the check value, or if `secret` has rows but there is neither keyset nor record, the app
  refuses to start and says how to restore the data volume. Only `JOFI_ACCEPT_SECRET_LOSS=true`
  accepts the loss: a new keyset is generated or adopted, recorded, logged loudly and written to the
  changelog; the stored secrets stay unreadable and must be entered again. A keyset without a record
  (older installation) is adopted.
- `SecretRepository` (`SecretStorePort`) stores only ciphertext in `secret`, encrypted through
  `SecretCipherPort` with the associated data `jofi:secret:<id>`: a ciphertext moved to another row
  does not decrypt, and a tampered one is reported as `Undecryptable`.
- A database dump alone reveals no secret; the data volume and the database together do. Backups
  must hold both (#26 decides how the key is backed up).

### Key rotation

Tink keysets hold several keys and every ciphertext names the key it was made with. Rotation is
therefore: add a new primary key to the keyset (older keys stay for decryption), re-encrypt every
row of `secret` with `SecretStorePort.put`, then remove the old key. This is not automated yet; it
needs a maintenance task (#64), and until then a compromised keyset means: delete it, start once
with `JOFI_ACCEPT_SECRET_LOSS=true` (a new one is generated) and re-enter the API keys.

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
- Export/import (#26) covers `user_account`, `secret` and `master_key_check`; `spring_session*` are
  ephemeral and are excluded (a restore starts logged out). The master keyset must travel with a
  backup of `secret`, or the restored instance refuses to start.
- Every request with a session reads the one `user_account` row; for a single user that is cheap.
- In-memory backoff counts reset on restart; an attacker cannot restart the app, so this is accepted.
- Spring Session 4.1.1's poms name a wrong licence (spring-session#3910); the licence gate allows
  exactly that version with a reason, so the next version is checked again.
