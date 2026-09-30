<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0039: AI providers through Spring AI and the vendor SDK cores on the guarded transport

- Status: accepted
- Date: 2026-09-30
- Source: issue #19 (M0-5) and its comments from the reviews of #11 and #18; refines ADR-0011,
  ADR-0032 and the "Requirements for the AI adapter" of ADR-0034

## Context

Jofi talks to Anthropic, OpenAI, Gemini, Mistral and OpenAI-compatible endpoints (spec §3.2) with
Spring AI behind `AiProviderPort` (ADR-0011, ADR-0032). Every byte must pass the SSRF guard
(ADR-0034), no prompt or key may reach a log (T4), and no provider type may leave `adapters/ai`.

Spring AI 2.0.1 (latest stable, for Spring Boot 4) builds its provider clients like this (read in
the 2.0.1 sources and the reference docs):

- `spring-ai-openai` and `spring-ai-anthropic` use the vendor SDKs (`openai-java-core` 4.49.0,
  `anthropic-java-core` 2.52.0). Their own OkHttp clients (`SpringAiOpenAiHttpClient`,
  `SpringAiAnthropicHttpClient`) take a customizer, but the customizer gets Spring AI's builder,
  which exposes neither an OkHttp `Dns`, nor redirect settings: an OkHttp binding of the guard, as
  ADR-0034 anticipated, cannot be installed through it. OkHttp also follows redirects by default.
- `spring-ai-google-genai` uses Google's `google-genai` SDK (OkHttp, plus Guava, Protobuf, Jackson 2
  and google-auth with its own HTTP client for credentials; `LocalTokenizerLoader` holds a static,
  unguarded `OkHttpClient`).
- `spring-ai-mistral-ai` uses a `RestClient` for calls but a `WebClient` for streaming, which needs a
  reactive connector the guard does not have.
- Spring's `HttpComponentsClientHttpResponse.close()` drains the body, so a cancelled completion
  over the `aiHttpRequestFactory` bean would be read to its end (and billed).
- Spring AI logs prompts in some paths, even at WARN (`No choices returned for prompt: ...`), and
  the SDKs log bodies when `OPENAI_LOG`/`ANTHROPIC_LOG` say `debug`.

The SDK *cores* have no transport of their own: a client needs a `com.openai.core.http.HttpClient`
(or the Anthropic equivalent) handed to its `ClientOptions`, and Spring AI's model builders accept
prebuilt SDK clients (`OpenAiChatModel.builder().openAiClient(..).openAiClientAsync(..)`,
`AnthropicChatModel.builder().anthropicClient(..)...`, `OpenAiEmbeddingModel.builder().openAiClient(..)`).

## Decision

- **Two Spring AI model modules only**: `spring-ai-openai` and `spring-ai-anthropic` 2.0.1 (BOM
  `spring-ai-bom`), no starters, so nothing is auto-configured. OkHttp is excluded from both: it is
  only used by Spring AI's own client builders, so a forgotten client fails loudly instead of going
  around the guard. Docs: https://docs.spring.io/spring-ai/reference/2.0/api/chat/openai-chat.html,
  https://docs.spring.io/spring-ai/reference/2.0/api/chat/anthropic-chat.html and the 2.0.1 javadoc of
  the model builders.
- **Gemini and Mistral through their OpenAI-compatible APIs** with the OpenAI module, as the Spring AI
  reference documents for Mistral ("OpenAI API Compatibility") and Google documents for Gemini
  (https://ai.google.dev/gemini-api/docs/openai, base URL
  `https://generativelanguage.googleapis.com/v1beta/openai`; streaming, tools, embeddings and model
  listing supported). Trade-offs: Google still calls this compatibility beta; Gemini- and
  Mistral-specific features (safety settings, OCR, cached content) are out of reach until a native
  module can run on the guarded transport. Spring AI always sends `stream_options` when streaming;
  Mistral's API reference does not list it, so Mistral streaming is covered by fixtures only.
- **One transport, in `adapters/net`**: `GuardedAiTransport` runs every SDK request on the Apache
  HttpClient that already carries the guard (`GuardedDnsResolver`, the allowlist of configured AI
  endpoints, no redirects, cookies, retries or system proxies, 10 s connect / 5 min read). It drops
  the SDKs' `X-Stainless-*` telemetry headers (OS, architecture, runtime versions) and their
  `User-Agent`. Closing an unfinished response cancels the exchange instead of draining it, so a
  cancelled stream stops at once. Every exchange has one overall deadline (the SDK's request
  timeout, at most 15 minutes), so a stream that trickles forever is still cut off. The pool allows
  20 connections per provider and 50 in total, and a call waits at most 10 s for a pooled connection
  (Apache's default is 3 minutes), then fails as unavailable instead of hanging.
  The earlier `aiHttpRequestFactory` bean is removed: no AI client uses a `RestClient`.
- **One bridge per call.** `OpenAiSdkHttpClient` and `AnthropicSdkHttpClient` implement the two
  SDKs' `HttpClient` interfaces over the transport; the adapter takes a fresh one per call and
  closes it when the call ends. Closing aborts whatever the call left open, at the transport and not
  through the SDK. Two SDK behaviours make this necessary. First, the SDKs close a cancelled stream
  through a `BufferedReader` that waits for the read in progress, so a stalled provider would keep
  the connection until the read timeout. Second, the Anthropic SDK wraps the request future in its
  logging layer, so a cancellation before the headers never reaches the transport, and the late
  response would stay leased from the pool. The stream collector also checks for cancellation
  before subscribing, and the bridges close any SDK response that is collected unclosed (a
  `Cleaner`, like the SDKs' own `PhantomReachable*` wrappers).
- **The adapter (`setup.adapter.ai`, module `adapters/ai`)** builds SDK clients per call from
  `ClientOptions` with the injected transport, the provider's endpoint, the key read through
  `SecretStorePort` just before the call, `maxRetries(0)` (the caller decides about retries from the
  sealed result) and `logLevel(OFF)`; nothing is read from the environment (an architecture rule
  bans the SDKs' `fromEnv()`). All clients share one stream executor and one sleeper. The executor
  is passed as a plain `Executor`, because the SDKs take ownership of an `ExecutorService` and shut
  it down when any client is closed or collected, which would stop every later stream. The sleeper
  is a no-op `Sleeper`, because the default one starts a `Timer` thread per client. Anthropic's key and API
  version go in as `x-api-key`/`anthropic-version` headers, which is what the SDK's default backend
  adds. Tools are declarations only; Spring AI 2.0's chat models return tool calls without running
  them. Every exception, including Reactor- and future-wrapped ones, maps to an `AiResult`:
  401/403 authentication, 429 rate limited with `Retry-After` seconds, 408/5xx/529 and I/O failures
  unavailable, context-window messages context too long, "does not support tools" capability missing,
  anything else rejected with its status. A `LinkageError` (a missing class, e.g. if Spring AI ever
  fell back to its excluded OkHttp client) is logged as an error and reported as unavailable, so
  nothing crosses the port. Logs name provider kind, model and result kind only;
  `application.yaml` switches off `org.springframework.ai`, `com.openai` and `com.anthropic`.
- **Capabilities**: `ModelCatalogPort` (new, `setup.application.port`) lists a provider's models
  (`GET /models` of each API) and combines them with a table of known model families (tool use,
  streaming, context size, embeddings, speech-to-text, text-to-speech) into `DETECTED` profiles;
  Anthropic's reported `max_input_tokens` replaces the table's context size. Models of an
  OpenAI-compatible endpoint have no known capabilities until the user sets them (#23), which also
  stores the profiles in `ai_model_capability` without overwriting user corrections.
- **Architecture rule**: `onlyTheNetAdapterMakesOutboundHttpCalls` keeps banning `com.openai..` and
  `com.anthropic..` everywhere except for a named list of SDK types in `setup.adapter.ai` (module
  `adapters/ai` and package both required, like the net exemption): the client interfaces and
  implementations, `ClientOptions` and its builder, `LogLevel`, `Sleeper`, `AutoPager`, the `HttpClient`
  interface as a type, `Headers`, the service and I/O exception types, and the model listing types.
  Spring AI's own builders (`org.springframework.ai.openai.setup..`, `..openai.http..`,
  `..anthropic.http..`, `AnthropicSetup`) and `com.google.genai..` are banned for everyone. Fixture
  tests prove that the exemption needs the module and that the SDK types stay banned elsewhere.
- **SDK versions** stay at the ones Spring AI 2.0.1 is built and tested against (its BOM does not
  manage them); newer SDK releases (4.72.0, 2.66.0) change the model classes Spring AI compiles
  against. They move with the next Spring AI release.

## Consequences

- One guard, one transport, one allowlist for every AI byte; the guard's tests cover the AI path
  (blocked internal destination, metadata address blocked even when allowlisted, no redirects,
  abort on close).
- Jackson 2 is on the runtime classpath (the SDK cores), with the catalog's security override.
- Adding a provider type that needs its own SDK transport (Google GenAI natively, voice providers in
  M5) means another bridge in `adapters/net` and a reviewed addition to the SDK type list.
