---
name: egress
title: Egress
triggers: ["backend/**", "frontend/src/**", "**/Dockerfile", "compose*.yaml"]
blocking: ["high"]
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: egress

All outbound network traffic from the backend goes through `backend/adapters/net`, which enforces the
SSRF guard (block private ranges, metadata endpoints, non-http schemes; timeouts; size limits; robots.txt
for page watchers). The only other allowed egress is the AI provider client in `adapters/ai`, which must
use the HTTP client provided by `adapters/net`.

Look for:
- Any HTTP/socket client created outside `adapters/net` (RestClient, WebClient, java.net.http.HttpClient,
  OkHttp, Ktor client, URL.openStream, JGit remote operations, Tika fetching remote resources). (high)
- URLs from users, postings or pages fetched without the SSRF guard. (high)
- Redirects followed without re-validating the target. (high)
- The `pdf` (Gotenberg) container given network access, or HTML templates that load remote resources. (high)
- Frontend code calling third-party origins (fonts, CDNs, analytics). (medium)

Ignore: calls inside tests that use WireMock or the fake AI provider.

Bad: `RestClient.create().get().uri(postingUrl)` in `adapters/scanners`.
Good: `scannerHttp.fetch(postingUrl)` where `scannerHttp` is the `OutboundHttpPort` implemented in `adapters/net`.
