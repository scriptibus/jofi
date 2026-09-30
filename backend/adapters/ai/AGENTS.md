<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/ai

The only place where AI provider types appear (ADR-0011, ADR-0032, ADR-0037). **Protected path**:
every change is reviewed by Lucas. Package: `io.github.scriptibus.jofi.setup.adapter.ai`.

- `SpringAiProviderAdapter` implements `AiProviderPort`: complete, stream (with cancellation) and
  embed for exactly the given `ResolvedModel`. No routing, filtering or metering; that is the AI
  gateway (#20), the only caller of the port.
- `ModelCatalogAdapter` implements `ModelCatalogPort`: the provider's model listing plus
  `CapabilityTable` (known model families) as `DETECTED` capability profiles.
- `ProviderModels` builds the vendor SDK clients and Spring AI models per call. Anthropic uses
  `spring-ai-anthropic`; OpenAI, Gemini, Mistral and OpenAI-compatible endpoints use
  `spring-ai-openai` (Gemini and Mistral through their OpenAI-compatible APIs, `ProviderEndpoints`).
- `ProviderFailures` maps every exception to an `AiResult`; `StreamCollector` consumes streams on the
  caller's thread and aborts them on cancellation; `ApiKeys` reads keys through `SecretStorePort`.

Rules:
- No HTTP client here. Each call gets fresh guarded bridges from `adapters/net`
  (`OpenAiSdkHttpClient`, `AnthropicSdkHttpClient`, factories wired in bootstrap) through
  `ClientOptions.httpClient(..)`, and closes them when the call ends (`ChatCall`, `EmbeddingCall`,
  `withOpenAi`/`withAnthropic`), which aborts what the call left open. Every Spring AI model gets
  prebuilt SDK clients. Never let Spring AI build its own (no starters, no
  `OpenAiSetup`/`AnthropicSetup`, no `httpClientBuilderCustomizer`); OkHttp is excluded on purpose.
- Only the SDK types named in `AdapterRules.AI_ADAPTER_SDK_TYPES` may be used; adding one needs
  review. No SDK or Spring AI type leaves this package; ports return sealed results, never throw.
- SDK clients: `maxRetries(0)`, `logLevel(OFF)`, nothing from the environment (`fromEnv()` is banned
  by an architecture rule), the shared stream executor as a plain `Executor` (the SDKs shut down an
  `ExecutorService` they are given) and the shared no-op sleepers (the default starts a thread per
  client). `ProviderModelsTest` guards both.
- Nothing crosses the port: exceptions map to `AiResult`, a `LinkageError` too (logged as an error).
- Never log prompts, answers, tool arguments or keys: provider kind, model and result kind only.
  `application.yaml` switches Spring AI's and the SDKs' loggers off; `LogPrivacyTest` reads that file.
- Tests: WireMock on loopback behind the real guarded transport (`ProviderStub`), recorded provider
  responses under `src/test/resources/fixtures` (streams as JSON arrays of events). Cancellation is
  checked on a raw socket (`StallingProvider`), which sees the client hang up. No real keys, no
  internet.
