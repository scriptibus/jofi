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

1. **Browser → app** (REST, WebSocket, SPA). Localhost by default; login required when exposed.
2. **External MCP clients → app.** Off by default; per-client revocable bearer tokens.
3. **App → AI providers.** Only the providers the user configured.
4. **App → the web** (BA API, ATS feeds, career pages, pasted URLs). Only via `adapters/net`.
5. **Untrusted content → AI**: postings, web pages, uploaded documents, (later) emails.
6. **Containers**: `app`, `worker`, `db`, `pdf` (Gotenberg, no network).

## Threats and mitigations

| # | Threat | Mitigation | Checked by |
|---|---|---|---|
| T1 | SSRF through user- or posting-supplied URLs (internal services, cloud metadata, file://) | All fetches go through `adapters/net`: scheme allowlist, private/link-local range block after DNS resolution, redirect re-validation, timeouts, size limits | egress lens, architecture tests, unit tests of the guard |
| T2 | Prompt injection from postings, pages or uploads makes the AI act | Scoring pipeline has no tools; chat treats content as data; deletes and outward actions need server-enforced confirmation; knowledge changes are proposals | untrusted-input and human-in-the-loop lenses, MCP contract tests |
| T3 | Personal data sent to AI against the user's wish | "Never send to AI" filter in the application layer before any provider call and on MCP tool results | privacy lens, tests |
| T4 | Secrets or PII in logs, errors or exports | Structured logging without payloads; secrets encrypted with Tink; redaction in error responses | privacy lens, gitleaks |
| T5 | Unauthorised access when exposed on a network | Login required, argon2id, secure session cookie, CSRF protection, HTTPS via Tailscale or Caddy | security lens, e2e auth tests |
| T6 | Malicious or compromised dependency, action or image | Dependency verification + locking, pinned SHAs/digests, minimum release age, OSV-Scanner, dependency-review, Trivy, licence allowlist | security workflow |
| T7 | Agent weakens its own checks | Lenses, workflows, hooks and AGENTS.md are protected paths; CODEOWNERS; merge gate | risk classifier, branch protection |
| T8 | Data loss (failed AI call, bad migration, disk failure) | Background jobs with retries; migrations reviewed by a human; export/backup and restore with round-trip test | portability lens, CI round-trip test |
| T9 | Page watcher abuses third-party sites | robots.txt respected, low default frequency with jitter, identifying user agent | scanner tests |

## Out of scope (for now)

Multi-user or multi-tenant operation, hostile local users on the same machine, physical access to the host.
