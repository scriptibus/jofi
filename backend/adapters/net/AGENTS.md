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
  retries, auth cache and system proxies off). Its pooled connections stay closed once closed
  (`StaysClosedConnection`): HttpClient 5.6 would still connect a socket for a request cancelled
  while it resolves or connects, and leave that socket open (#137).
- `DestinationAllowlist` unlocks internal (never link-local/metadata/reserved) addresses for exact
  host:port destinations. Posting and page fetches get `NONE`; only the AI transport
  (`GuardedAiTransport`, wired in `bootstrap` `setup.config.AiHttpConfiguration`) gets the configured
  provider base URLs.
- `GuardedAiTransport` (ADR-0040) is the transport of the AI vendor SDKs: no redirects, SDK telemetry
  headers dropped, one overall deadline per exchange, 20 connections per provider and a 10 s wait
  for a pooled connection. Closing an unfinished response aborts it instead of draining it.
  `OpenAiSdkHttpClient` and `AnthropicSdkHttpClient` implement the SDKs' `HttpClient` interfaces over
  it, one instance per AI call: closing one aborts that call's open exchanges (`ExchangeScope`),
  cancellation of the SDK's future is forwarded (`SdkFutures`), and an SDK response collected
  unclosed is closed (`UnclosedResponses`). They wrap I/O failures in the SDKs' I/O exceptions.

Rules:
- Nothing else in the backend may use an HTTP client or socket (architecture test
  `onlyTheNetAdapterMakesOutboundHttpCalls`, which exempts this module only, not the package
  elsewhere). A new transport (e.g. OkHttp for the AI SDKs) gets its
  binding to `DestinationGuard` here, never a second guard.
- Never log header values, paths, queries or bodies; host and result kind only.
- Tests: table-driven classifier tests for every range you touch, WireMock for adapter behaviour
  (WireMock listens on loopback, so tests allowlist its exact destination), a stub `HostResolver`
  for DNS behaviour. Show that a new check fails without the change.
