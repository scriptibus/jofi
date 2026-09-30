<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Threat model

Version 0.1, 2026-09-30. Scope: a single-user Jofi instance run with Docker Compose on a laptop, NAS or
home server (spec §3.1, §13; proposal §4.6). Update this file whenever a PR adds a new data flow,
external call, input channel or privileged action.

## Assets

| Asset | Why it matters |
|---|---|
| Knowledge base (CV, Zeugnisse, salary history, preferences, stories) | Highly personal; loss or leak is the worst case |
| Applications, contacts, interview notes | Personal data, including third parties (contacts) |
| AI provider API keys, MCP client tokens, login password | Direct cost and access |
| Generated and frozen documents | Evidence of what was sent to whom |

## Trust boundaries and entry points

1. **Browser → app** (REST, WebSocket, SPA). Localhost by default; every API call needs a login session (ADR-0035).
2. **External MCP clients → app.** Off by default; per-client revocable bearer tokens.
3. **App → AI providers.** Only the providers the user configured.
4. **App → the web** (BA API, ATS feeds, career pages, pasted URLs). Only via `adapters/net`.
5. **Untrusted content → AI**: postings, web pages, uploaded documents, (later) emails.
6. **Containers**: `app`, `worker`, `db`, `pdf` (Gotenberg, no network).

## Threats and mitigations

| # | Threat | Mitigation | Checked by |
|---|---|---|---|
| T1 | SSRF through user- or posting-supplied URLs (internal services, cloud metadata, file://) | All fetches go through `adapters/net` (ADR-0034): scheme allowlist; DNS resolved once, every address checked and the connection pinned to it (no rebinding); loopback, private, CGNAT, link-local/metadata, multicast, unspecified, reserved and IPv4-mapped variants blocked; redirects re-validated per hop with a limit; credentials dropped across origins; timeouts, size and content-type limits. Only the AI client may reach the configured provider URLs (exact host:port allowlist). Gaps the architecture rule cannot see (auto-configured clients, libraries that fetch on their own, JVM SOCKS settings) are listed in ADR-0034 | egress lens; `AdapterRulesTest`, `AdapterRulesFixtureTest` (only the `adapters/net` module makes HTTP calls); `AddressClassifierTest`, `DestinationGuardTest` (incl. rebinding, DNS timeout, address cap), `OutboundHttpAdapterTest` (WireMock: redirects, limits, timeouts, rebinding), `OutboundHttpLimitsTest` (deadline, gzip bomb, endless body, proxy settings), `AiRequestFactoryTest`, `OutboundHttpWiringTest` |
| T2 | Prompt injection from postings, pages or uploads makes the AI act | Scoring pipeline has no tools; chat treats content as data; deletes and outward actions need server-enforced confirmation; knowledge changes are proposals | untrusted-input and human-in-the-loop lenses, MCP contract tests |
| T3 | Personal data sent to AI against the user's wish | "Never send to AI" filter in the application layer before any provider call and on MCP tool results | privacy lens, tests |
| T4 | Secrets or PII in logs, errors or exports | Structured logging without payloads; secrets encrypted with Tink (AES-256-GCM, secret id as associated data, master keyset `0600` in the data volume, ADR-0035); passwords, tokens and secrets redacted in `toString`; redaction in error responses; background jobs carry ids only, store failure reason codes instead of exception messages, and read stored job JSON through a class allowlist that quarantines anything Jofi did not write (ADR-0038) | privacy lens, gitleaks; `JobStoreTest`, `JofiJobRequestHandlerTest`, `JobRunrJobLogAdapterTest`, `BackgroundJobsTest` (no personal data in the job store); `LogCanaryTest` (verbose logs contain no password, setup token, session id, CSRF token, secret or key material), `StartupSafetyTest` (a lost keyset is never silently replaced), `SecretStoreTest`, `TinkSecretCipherAdapterTest` (tamper, moved ciphertext, file permissions), `DatabaseConfigurationTest` |
| T5 | Unauthorised access when exposed on a network | Login required for every API call, argon2id (OWASP parameters), session id changed at login, `HttpOnly`/`SameSite=Lax`/`Secure`-on-HTTPS cookies, SPA CSRF tokens, backoff per client and overall, one-time setup token for every first run, sessions bound to the account and limited to 30 days, other sessions end on password change, password recovery only from the server (ADR-0035). Limitation: without real client addresses (rootless Podman, Docker userland proxy, untrusted proxy) all clients share one backoff key; HTTPS via Tailscale or Caddy | security lens; `AuthSecurityTest` (401 problem details for every non-public mapping, CSRF, cookie flags, session fixation, backoff, sessions in PostgreSQL), `TrustedProxyTest`, `StartupSafetyTest`, `ResetPasswordUseCaseTest`, `Argon2PasswordHasherAdapterTest`, use-case tests; e2e auth tests with #22 |
| T6 | Malicious or compromised dependency, action or image | Dependency verification + locking, pinned SHAs/digests, minimum release age, OSV-Scanner, dependency-review, Trivy, licence allowlist | security workflow |
| T7 | Agent weakens its own checks | Lenses, workflows, hooks and AGENTS.md are protected paths; CODEOWNERS; merge gate | risk classifier, branch protection |
| T8 | Data loss (failed AI call, bad migration, disk failure) | Background jobs with retries and exponential backoff in the worker, failed jobs kept with their ids, one catch-up run after downtime (ADR-0038); migrations reviewed by a human; export/backup and restore with round-trip test | portability lens, CI round-trip test |
| T9 | Page watcher abuses third-party sites | robots.txt respected, low default frequency with jitter, identifying user agent | scanner tests |

## Out of scope (for now)

Multi-user or multi-tenant operation, hostile local users on the same machine, physical access to the host.
