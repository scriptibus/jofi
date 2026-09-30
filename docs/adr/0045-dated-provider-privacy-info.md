<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0045: Dated provider privacy info with quoted sources

- Status: accepted
- Date: 2026-09-30
- Source: issue #138 (split from #23); spec §3.2; precedent ADR-0043 (dated price table)

## Context

The setup wizard (#25) shows, per AI provider, whether zero data retention (ZDR) is available, whether
the provider promises not to train on API data, and where data is processed, "loaded from a dated info
file with links" and always with a disclaimer that users must verify the terms (spec §3.2). Provider
terms change often, differ between API and consumer apps, and are easy to misremember. A wrong privacy
claim is worse than none: users decide on it where their CV and personal data go.

## Decision

- `provider-privacy.json` in `adapters/ai` resources (next to `price-table.json`), read by
  `ProviderPrivacyFile` once at startup into the domain's `ProviderPrivacyCatalog`. A broken file
  stops startup. It is maintained by hand; Jofi never fetches provider pages at runtime.
- One entry per `ProviderKind` (the catalog refuses a missing or duplicate kind) with its own
  `checkedOn`. Each of the three questions is a claim: a status (`YES`, `ON_REQUEST`, `CONDITIONAL`,
  `NO`, `DEPENDS_ON_ENDPOINT`, `UNKNOWN`), a short summary in English and German, and at least one
  piece of evidence: a **verbatim quote** and the https URL of the provider's official page it was read
  on. Only API terms count. Nothing is written from memory; what the pages do not state is `UNKNOWN`
  or "not stated" in the summary. The OpenAI-compatible entry is `DEPENDS_ON_ENDPOINT` throughout,
  citing Ollama's and LM Studio's docs for "a local server keeps data on your machine".
- Quotes stay in the provider's language (English); summaries are data in both UI languages, so the
  UI shows them without its own translation. The disclaimer comes as a Paraglide key plus its EN/DE
  text, so the UI can render it through Paraglide.
- **Staleness: 6 months.** `staleAfterMonths` is in the file; `GET /api/setup/providers/privacy` marks
  every entry read longer ago as `stale`, and the UI flags it. Six months matched how often the pages
  read on 2026-09-30 had changed (several showed updates within the last months) while keeping the
  refresh chore to twice a year.
- `ProviderPrivacyFileTest` checks one entry per kind, https sources on the provider's own hosts for
  the vendors, summaries in both languages, and that malformed files fail to load.

## Consequences

- Refreshing is manual: re-read the sources, look for newer official pages, update claims and dates,
  and list the URLs in the PR (see `backend/adapters/ai/AGENTS.md`).
- A new `ProviderKind` cannot ship without its privacy entry (the catalog and the test fail).
- Claims are summaries of public terms, not legal advice; the disclaimer says so, and contracts or
  approvals (ZDR, data residency) that a user has agreed individually are not reflected.
