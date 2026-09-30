<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0034: SSRF guard with pinned DNS, and an allowlist for AI endpoints

- Status: accepted
- Date: 2026-09-30
- Source: issue #18 (M0-4), threat model T1, docs/spec/04-tech-stack-proposal.md §4.1, §4.6; builds on
  ADR-0011 and ADR-0032; the AI client part is refined by ADR-0037

## Context

Jofi fetches URLs that come from the user, from postings and from pages (T1). A URL can name an
internal service, the cloud metadata endpoint or `file:`; a public host name can resolve to a private
address, or resolve to a public one when checked and to a private one when connected (DNS
rebinding); a harmless page can redirect inside. At the same time a user may run the AI model
locally (Ollama, LM Studio, vLLM on `localhost`, a Compose service or a Tailscale address), so the AI
client must be able to reach exactly that one internal endpoint without opening the rest of the
network to posting URLs.

The JDK `HttpClient` has no per-client DNS hook (only the JVM-wide `InetAddressResolverProvider`),
so it cannot connect to the addresses it checked; pinning an IP in the URL breaks TLS SNI and
certificate checks. Spring's `RestClient` is a facade over a `ClientHttpRequestFactory` and adds
nothing to the problem.

## Decision

- **Apache HttpClient 5** (5.6.4, managed by the Spring Boot 4.1.1 BOM) in `adapters/net`, the only
  module allowed to use an HTTP client (architecture test `onlyTheNetAdapterMakesOutboundHttpCalls`:
  JDK/Apache/OkHttp/Ktor/Jetty/Netty/Vert.x/JAX-RS/AsyncHttpClient/Feign/Retrofit clients, the
  OpenAI and Anthropic SDKs, jsoup, Spring's `RestClient`/`RestTemplate`/`WebClient`,
  request-factory implementations and Boot's HTTP client builders, TCP/TLS/UDP sockets and
  channels, `java.net.URL` itself (use `URI`), `URLClassLoader`, `ImageIO.read(URL)` and Kotlin's
  `URL.readText`; the exemption requires both the package and the `adapters/net` module, checked by
  class location). Its `DnsResolver` is called for every new connection and the client
  connects to exactly the addresses it returns. `GuardedDnsResolver` resolves once, classifies
  **every** address, and returns them only if all are allowed, so the checked addresses are the
  connected ones and TLS still verifies the host name. The lookup runs on a virtual thread and is
  abandoned after 5 s or the fetch's remaining time; at most 4 addresses are returned, so a name
  with many dead addresses cannot multiply connect timeouts.
  Docs: https://hc.apache.org/httpcomponents-client-5.6.x/ and the 5.6.4 sources of
  `DnsResolver`, `DefaultHttpClientConnectionOperator`, `HttpClientBuilder`.
- **Address classes** (`AddressClassifier`, IANA special-purpose registries): only global unicast is
  public. Loopback, RFC 1918, shared/CGNAT and IPv6 unique local are *internal*; link-local
  (`169.254.0.0/16`, `fe80::/10`, so also `169.254.169.254`), cloud metadata outside link-local
  (`fd00:ec2::254`, Alibaba `100.100.100.200`, Azure WireServer `168.63.129.16`, classified
  before the CGNAT/public ranges that contain them), multicast,
  unspecified, documentation, benchmarking, 6to4, Teredo, NAT64 local-use, deprecated and
  unassigned ranges are *never reachable*. IPv4-mapped and NAT64 (`64:ff9b::/96`) addresses are
  classified by the embedded IPv4 address.
- **`OutboundHttpPort`** (`OutboundHttpAdapter`): scheme allowlist `http`/`https`; user info and
  fragments stripped; redirects followed by hand (301/302/303/307/308) up to the request's limit,
  each hop re-checked; on a cross-origin redirect only `Accept` and `Accept-Language` survive, so
  `Authorization`, `Cookie` and other caller headers never reach another origin; no cookie store,
  no automatic retries, no auth cache, no system proxy settings; `Host`, `User-Agent` and framing
  headers cannot be set by callers. One deadline covers the whole fetch: DNS, connect, TLS
  handshake and socket reads each get min(configured, remaining time), resolved per connection,
  and a watchdog bounds the caller's wait. The fetch runs on a virtual thread; the caller waits
  at most the timeout, then closes the client immediately (aborting sockets mid-connect or
  mid-read) and returns `Timeout`, however slowly a resolver or server dribbles. Declared and actual body sizes are capped (after decompression)
  without draining the rest; accepted content types are compared without parameters; `Retry-After`
  (seconds or HTTP date) is returned with `HttpError`. Every expected failure is a `FetchResult`,
  nothing is thrown. Each fetch uses a fresh client, so a request's timeout applies to connecting
  and no pooled connection outlives it. The identifying user agent is
  `Jofi/<version> (+https://github.com/scriptibus/jofi)` (T9). Logs carry the host and the result
  kind only; HttpClient's header and wire loggers are switched off in `application.yaml` (T4).
- **Allowlist per destination.** `DestinationAllowlist` names exact destinations (normalized host +
  port, so `localhost` and `127.0.0.1` differ) that may resolve to *internal* addresses. It never
  unlocks never-reachable classes, so an allowlisted name still cannot reach the metadata service.
  Fetches of user- or posting-supplied URLs get `DestinationAllowlist.NONE` (wired in
  `shared.config.OutboundHttpConfiguration`).
- **AI client.** `bootstrap` wires the AI transport (`GuardedAiTransport`, ADR-0037: the same guarded
  HttpClient, no redirects, 10 s connect / 5 min read timeout, idle pooled connections evicted after
  30 s so a removed provider's connection closes soon) in `setup.config.AiHttpConfiguration`. Its
  allowlist is the set of base URLs of the configured AI providers (`ProviderConfigPort`), read on
  each new connection so a changed configuration applies immediately (for new connections; an open
  pooled connection lives until it idles out); if the store is absent or fails, the allowlist is
  empty (fail closed). AI response sizes are **not** capped: completions stream and the destination
  is one the user configured. (Until ADR-0037 this was a `ClientHttpRequestFactory` bean,
  `aiHttpRequestFactory`.)
- **Requirements for the AI adapter (#19)**, as implemented by ADR-0037: no provider client builds
  its own HTTP client. Spring AI's OkHttp customizers cannot set a `Dns` or redirects, so instead of
  an OkHttp binding the vendor SDK cores get Jofi's own implementation of their `HttpClient`
  interface over the guarded transport (`OpenAiSdkHttpClient`, `AnthropicSdkHttpClient`); OkHttp is
  not on the classpath. No AI client uses a `RestClient` or `WebClient`. The exemption
  `setup.adapter.ai` needs from the architecture rule is a named list of SDK types (ADR-0037).
  Docs: https://docs.spring.io/spring-ai/reference/2.0/ and the Spring AI 2.0.1 sources.

## What the architecture rule cannot see

ArchUnit checks our own compiled classes. It does not see:

- HTTP clients that Spring Boot or Spring AI auto-configure and use internally (for example a
  `RestClient.Builder` bean that a starter picks up, or Spring AI's own OkHttp clients). Every
  starter that brings a client needs review, a test that its client goes through the guard, and
  the rules above.
- Libraries that fetch on their own: XML parsers resolving external entities or schemas, Tika
  fetching remote resources, image or PDF libraries loading linked content, JGit remotes. Each such
  library needs its network features switched off where it is introduced.
- JVM-wide settings: `socksProxyHost` may still apply to HttpClient's plain sockets; the JVM's
  system properties are deployment configuration, not user input.

The egress lens reviews these cases on every PR that touches the backend.

## Consequences

- A URL can only reach public addresses, whatever its name resolves to and wherever it redirects;
  a local model works only at the URL the user configured.
- No connection reuse across fetches: acceptable for the volumes of a single-user app.
- The guard is transport-agnostic (`DestinationGuard`); new HTTP clients plug into it in
  `adapters/net` instead of reimplementing the checks.
- Proxy support, robots.txt and HTML-to-text are out of scope (M4, #34).
