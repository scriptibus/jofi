<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/ai

The only place where AI provider types appear (ADR-0011, ADR-0032, ADR-0040). **Protected path**:
every change is reviewed by Lucas. Package: `io.github.scriptibus.jofi.setup.adapter.ai`; adapters of other contexts
that call `LlmPort` for a task (never a provider) live here as `<context>.adapter.ai` (ADR-0051).

- `AiGatewayAdapter` (ADR-0043) implements `LlmPort` and `EmbeddingPort`, and is the only caller
  of `AiProviderPort` (architecture rules). Per call: `AiRouter` (assignment, provider, capabilities,
  read once) → capability check for what the call needs → `AiMeter.admit` (budget, non-essential
  tasks only) → `NeverSendGuard` (the "never send to AI" filter over `AiVisibilityPort`, fail closed)
  → provider call → `AiMeter.record` (one `CostEntry`; unknown cost stays null, never guessed).
- `price-table.json` (resources, next to `PriceTableFile`): list prices per model with the provider's
  page and the day read. Only add a row you read on the official page; bump `checkedOn` with every
  change. `PriceTableFileTest` checks sources and spot prices. `AiMeter` prices a call on an OpenAI-compatible
  model from the user's price (`ModelPricePort`, ADR-0055), every other kind from the table.
- `provider-privacy.json` (resources, next to `ProviderPrivacyFile`, #138, spec §3.2): per provider kind
  what the API terms say about zero data retention, training and data location, each claim with a
  status, a DE/EN summary and short **verbatim** quotes from the provider's official pages. Never write a
  fact from memory; what the pages do not say is `UNKNOWN` or "not stated" in the summary. API terms
  only, not consumer apps. Refresh: re-read every source (and look for newer official pages), update
  the claims, set each entry's and the file's `checkedOn`, list every URL in the PR. The API flags
  entries older than `staleAfterMonths` (6) as stale, so refresh at least twice a year.
  `ProviderPrivacyFileTest` checks one entry per `ProviderKind`, https sources on official hosts, and
  that a broken file stops startup.
- `SpringAiProviderAdapter` implements `AiProviderPort`: complete, stream (with cancellation and
  usage totals through `onUsage`) and embed for exactly the given `ResolvedModel`. No routing,
  filtering or metering; that is the gateway.
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
- The filter runs on every message part and embedding input; never pass a request to
  `AiProviderPort` that did not come out of `NeverSendGuard`. New `AiResult` variants from the
  gateway: `PrivacyFilterFailed` (fail closed) and `Withheld` (flagged embedding input).
- Never log prompts, answers, tool arguments or keys: task, provider kind, model, result kind and
  counts only.
  `application.yaml` switches Spring AI's and the SDKs' loggers off; `LogPrivacyTest` reads that file.
- Gateway tests: `AiGatewayAdapterTest` (spy provider, in-memory stores from `GatewayFixtures`),
  `AiGatewayWireTest` (real adapter, WireMock; asserts on the bodies the provider received);
  bootstrap `AiGatewayPrivacyTest` runs the wired app against PostgreSQL.
- Tests: WireMock on loopback behind the real guarded transport (`ProviderStub`), recorded provider
  responses under `src/test/resources/fixtures` (streams as JSON arrays of events). Cancellation is
  checked on a raw socket (`StallingProvider`), which sees the client hang up. No real keys, no
  internet.
