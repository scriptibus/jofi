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
- No HTTP client here. SDK clients get the guarded transport beans from `adapters/net`
  (`OpenAiSdkHttpClient`, `AnthropicSdkHttpClient`) through `ClientOptions.httpClient(..)`, and every
  Spring AI model gets prebuilt SDK clients. Never let Spring AI build its own (no starters, no
  `OpenAiSetup`/`AnthropicSetup`, no `httpClientBuilderCustomizer`); OkHttp is excluded on purpose.
- Only the SDK types named in `AdapterRules.AI_ADAPTER_SDK_TYPES` may be used; adding one needs
  review. No SDK or Spring AI type leaves this package; ports return sealed results, never throw.
- SDK clients: `maxRetries(0)`, `logLevel(OFF)`, nothing from the environment.
- Never log prompts, answers, tool arguments or keys: provider kind, model and result kind only.
  `application.yaml` switches Spring AI's and the SDKs' loggers off; keep `LogPrivacyTest` in sync.
- Tests: WireMock on loopback behind the real guarded transport (`ProviderStub`), recorded provider
  responses under `src/test/resources/fixtures` (streams as JSON arrays of events). No real keys,
  no internet.
