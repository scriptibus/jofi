<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/net

The only outbound HTTP client (threat model T1, ADR-0034). **Protected path**: every change is
reviewed by Lucas. Package: `io.github.scriptibus.jofi.shared.adapter.net`.

- `OutboundHttpAdapter` implements `OutboundHttpPort`: scheme allowlist, manual redirects with a
  limit and a re-check per hop, credentials dropped across origins, one deadline, body size cap,
  content-type allowlist, `Retry-After`. A watchdog bounds every fetch by its timeout (DNS,
  connect, TLS and reads also get the remaining time). Never throws; every failure is a `FetchResult`.
- `DestinationGuard` resolves a host once and checks every address with `AddressClassifier`;
  `GuardedDnsResolver` plugs it into Apache HttpClient 5, which then connects to exactly the checked
  addresses (no DNS rebinding). `GuardedHttpClients` builds every client (redirects, cookies,
  retries, auth cache and system proxies off).
- `DestinationAllowlist` unlocks internal (never link-local/metadata/reserved) addresses for exact
  host:port destinations. Posting and page fetches get `NONE`; only the AI transport
  (`GuardedAiTransport`, wired in `bootstrap` `setup.config.AiHttpConfiguration`) gets the configured
  provider base URLs.
- `GuardedAiTransport` (ADR-0037) is the transport of the AI vendor SDKs: no redirects, SDK telemetry
  headers dropped, and closing an unfinished response aborts it instead of draining it.
  `OpenAiSdkHttpClient` and `AnthropicSdkHttpClient` implement the SDKs' `HttpClient` interfaces over
  it and wrap I/O failures in the SDKs' I/O exceptions.

Rules:
- Nothing else in the backend may use an HTTP client or socket (architecture test
  `onlyTheNetAdapterMakesOutboundHttpCalls`, which exempts this module only, not the package
  elsewhere). A new transport (e.g. OkHttp for the AI SDKs) gets its
  binding to `DestinationGuard` here, never a second guard.
- Never log header values, paths, queries or bodies; host and result kind only.
- Tests: table-driven classifier tests for every range you touch, WireMock for adapter behaviour
  (WireMock listens on loopback, so tests allowlist its exact destination), a stub `HostResolver`
  for DNS behaviour. Show that a new check fails without the change.
