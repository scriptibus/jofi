<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0043: The AI gateway, the "never send to AI" filter and the cost meter

- Status: accepted
- Date: 2026-09-30
- Source: issue #20 (M0-6); spec §2, §3.2 (cost tracking), §4.1 ("never send to AI"), §13; threat
  model T3/T4; refines ADR-0011, ADR-0032, ADR-0040

## Context

ADR-0032 decided that one gateway decorator in `setup.adapter.ai` implements `LlmPort` and
`EmbeddingPort` and is the only caller of the provider-facing `AiProviderPort` (Spring AI, ADR-0040).
Three things were still open:

- How the gateway knows which content is flagged "never send to AI". Knowledge entries, the only
  flaggable items (spec §4.1), arrive in M2; the filter contract must exist now, fail closed, and let
  M2 plug in without touching callers. It must cover every part of a request: system prompts, user
  messages, earlier answers, tool call arguments, tool results and embedding inputs.
- Where prices come from. A cost that is guessed is worse than one that is unknown; OpenAI-compatible
  endpoints (Ollama, OpenRouter, ...) have no list price at all.
- How the budget check and the meter treat cancellations, failures and store errors.

## Decision

### Order of the gateway's steps

Per call: route (the task's assignment, provider config and capabilities, read once) → capability
check → budget check → "never send to AI" filter → provider call with the filtered request → meter.
Any step can end the call with a sealed `AiResult`; the provider is called only when all pass. An
unexpected exception before the provider call ends as `Unavailable` and sends nothing.

### Content carries its source (the shared marker)

- `shared.domain.ai.ContentPart` is `Plain(text)` or `Sourced(text, ContentSource)`; a
  `ContentSource` is a `ContentSourceType` (today only `KNOWLEDGE_ENTRY`) plus an id. `System`,
  `User` and `ToolResult` messages and embedding inputs are lists of parts; the old `String`
  constructors remain and make one `Plain` part. Callers mark every piece they copy from a stored
  item as `Sourced`, so it can be withheld by its identity, not only by its words.
- `shared.application.port.AiVisibilityPort.rulesFor(sources)` answers with `NeverSendRules`: a
  `SENDABLE`/`NEVER_SEND` verdict per source it knows, and **all** flagged values (texts such as an
  address line or a phone number) of all flagged items. It is in the shared kernel because the MCP
  result filter (#116) needs it too. Until M2, `NoKnowledgeYetAiVisibilityAdapter` (bootstrap) knows
  no source and no flagged value; the knowledge context replaces it.

### Enforcement: knowledge only ever travels as `Sourced`

The value scan is the second line of defence; the source mark is the first, and it only works if
knowledge text cannot reach the gateway unmarked. Rule (from the first M2 knowledge PR on, #136):

- The knowledge domain has a text type for entry content whose only way out toward AI is
  `asPromptPart(): ContentPart.Sourced` (carrying `ContentSource(KNOWLEDGE_ENTRY, id)`).
- Classes that depend on knowledge domain types must not use `ContentPart.Plain`, the `String`
  constructors of `LlmMessage.System`/`User`/`ToolResult`, or `EmbeddingRequest.ofTexts`. A
  Konsist/ArchUnit rule with a known-bad fixture enforces it (acceptance criterion of #136).
- `AiVisibilityPort` returns as flagged values each flagged entry's full text **and** each of its
  non-blank lines **and** each of its fields, so partial quotes are caught by the value scan too.

### The filter (`NeverSendFilter`, a pure domain service)

1. **Sources.** A `Sourced` part of a flagged source becomes `[withheld]`. A source without a
   verdict, an unavailable source or any exception refuses the whole call with
   `AiResult.PrivacyFilterFailed` (**fail closed**); nothing is sent. An embedding input from a
   flagged source refuses the whole request with `AiResult.Withheld` (embedding `[withheld]` would
   only pollute the index).
2. **Values.** The value scan runs on what goes on the wire: each message's parts **joined** as the
   provider adapter joins them (a value split across parts is found), earlier answers, embedding
   inputs, and tool definitions (description and schema). Matching (`ValueRedactor`):
   - text and values are compared after Unicode **NFKC** (full-width characters, no-break, narrow
     and thin spaces become plain ones), ignoring case;
   - whitespace in a value matches any run of `[\s\p{Z}]`, and invisible format characters
     (`\p{Cf}`: zero-width space and joiner, BOM) may sit between any two characters;
   - in a value made mostly of digits, any of `[\s\p{Z}\-/.()]` may sit between its characters, so
     `0170-1234567`, `0170/1234567`, `0170.123.45.67` and `+49 (0)170 1234567` match `0170 1234567`;
   - a value shorter than 4 letters or digits only matches as a whole word (flag creation in M2
     warns about such values);
   - the match ranges of all values are collected on the normalised text, merged where they overlap
     or touch, and each merged range is replaced **once**, so overlapping values ("Anna Schmidt",
     "Schmidt Str. 5") become one `[withheld]` and a marker is never redacted again.
3. **JSON.** Tool call arguments and tool schemas are JSON: values are redacted inside their decoded
   string values (so `\u00fc` escapes do not hide them), a number containing a value becomes the
   string `"[withheld]"`, and the JSON stays valid. Text that is not well-formed JSON is redacted as
   plain text.

The filtered request carries only `Plain` parts, so the provider adapter never sees a source.
Redaction was chosen over refusing the call for flagged content, because a document or chat prompt
that quotes the whole profile must still work without the flagged entries. The model sees that
something is missing.

Limits: the value scan cannot catch paraphrases, translations or other spellings (`ß`/`ss`), nor a
value that the model is told to reassemble from pieces. That is why knowledge must travel as
`Sourced` (above) and why the MCP result filter (#116) removes flagged entries before they are
serialised.

### Capability check

A call is refused with `CapabilityMissing` only for what **this call** needs: tool use when it
declares tools, streaming when it streams, embeddings for an embedding. The task's other
requirements (context size) stay warnings, because the spec lets the user pick a weaker model; a
prompt that does not fit comes back as `ContextTooLong`. A stored `ai_model_capability` profile wins;
without one, the adapter's table of known models answers.

### Prices and the meter

- `price-table.json` next to `PriceTableFile` (in `adapters/ai` resources) lists standard-tier text
  prices in USD per million tokens for Anthropic, OpenAI, Gemini and Mistral models, each with the
  provider's pricing page and the day it was read (2026-09-30). Only prices read on the official pages
  are listed; promotional prices with an end date, and rows whose long-context threshold was not
  printed, are left out. A model matches by its exact name (case-insensitive, Gemini's `models/`
  prefix removed): a similar name never borrows a price. A long-prompt tier applies to all tokens of a
  call above its threshold. OpenAI-compatible endpoints are never priced here (the user sets prices,
  #24). A broken file stops the app at startup.
- Cost = tokens × USD per million = micro-dollars exactly; rounded half up to whole micros once per
  call. Cached-input discounts, cache writes and audio prices are not modelled: the cost is an
  estimate, as the spec says.
- Every completed call is metered; a cancelled stream with the last usage total the provider reported
  before the cancel (`AiProviderPort.stream` now reports usage totals through `onUsage`); a failed
  call only if the provider already reported usage. **An unknown cost is `NULL`** (`CostEntry.
  estimatedCost: Money?`, migration `ai_cost_entry.cost_micros` nullable): no price for the model, or
  no usage reported. Tokens are always kept. A failing meter is logged and the answer still returned.
- Cost entries are the append-only meter itself and not user data, so they write no changelog entry
  (spec §13 audits applications, documents and knowledge).

### Budget

Only `MonthlyBudget.NON_ESSENTIAL_TASKS` (today `SCANNER_PRE_SCORING`) are checked: when the month's
known costs reach the cap they get `BudgetExceeded`; if the budget or the spending cannot be read they
do not run either (`Unavailable`). Everything the user triggers never waits for the budget. Months
are UTC calendar months (`BillingMonth`), like the providers' usage reports; the pause lifts at 00:00
UTC on the first. The cap is soft: calls already running still finish, and parallel jobs can overshoot
it by their own cost. Unknown costs count as zero, so an unpriced paid endpoint is not capped until
the user gives it a price (#24).

### Persistence and architecture

The `setup` repositories the gateway needs (`ProviderConfigRepository`, `ModelAssignmentRepository`,
`ModelCapabilityRepository`, `MonthlyBudgetRepository`, `CostEntryRepository`) are implemented here
over the existing tables; #23/#24 add the use cases and API on top. `CostEntryPort.totalBetween`
sums in SQL. New architecture rules: only `AiGatewayAdapter` implements `LlmPort`/`EmbeddingPort`, and
only it calls `AiProviderPort` methods (also inside `setup.adapter.ai`); fixtures prove a shortcut is
rejected.

## Consequences

- M2 implements `AiVisibilityPort` from the knowledge store and deletes the placeholder; knowledge
  reaches the gateway only as `Sourced`, enforced by a rule (#136). Nothing in the gateway changes.
- The price table needs care: a new model costs "unknown" until a verified row is added, and a price
  change needs a new `checkedOn` date and source.
- Every `LlmPort`/`EmbeddingPort` result can now also be `PrivacyFilterFailed` or `Withheld`.
- The e2e compose stack exercises the gateway once an endpoint triggers an AI call; until then
  `AiGatewayPrivacyTest` (bootstrap) drives the wired app against a wire-level fake provider.
