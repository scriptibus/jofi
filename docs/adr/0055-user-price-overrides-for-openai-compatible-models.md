<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0055: User prices for the models of OpenAI-compatible providers

- Status: accepted
- Date: 2026-10-02
- Source: issue #142 (split out of #24); spec §3.2 (cost tracking, budget), §13; refines ADR-0043

## Context

ADR-0043 prices calls from a dated table of the cloud providers' list prices and leaves the cost of
every OpenAI-compatible endpoint (Ollama, OpenRouter, vLLM, ...) unknown: such an endpoint has no list
price, and a similar name's price is never borrowed. Calls on those models are counted as
`unknownCostCalls` and never reach the monthly cap. The user knows what such a model costs (0 for a local
one, the provider's own price for OpenRouter) and must be able to say so.

## Decision

- **Where a price lives.** `ai_model_price_override` (`setup`): one row per **provider config and exact model
  name**, deleted with the provider (`ON DELETE CASCADE`). Two OpenAI-compatible providers can therefore price
  the same model name differently. The name is the one the task assignment and the cost entries carry (NFC,
  trimmed; no case folding: the user types it from the provider's own model list).
- **Unit and limits.** Micros of a US dollar per million input and per million output tokens (`bigint`), 0
  allowed (local models), at most 10,000 US dollars per million tokens (`ModelPriceOverride.
  MAX_MICROS_PER_MILLION`): above that it is a typo, and the largest possible call stays far from overflowing
  `Long`. The check constraints equal the domain's limits, never stricter. The cost of a call is
  `micros × tokens`, rounded half up to whole micros once per call, like the price table (`TokenPrice`).
- **Which models.** Only models of `OPENAI_COMPATIBLE` providers. Cloud providers keep the verified table; an
  attempt to price one is refused (`SetupResult.PriceNotAllowed`, `409 price-not-allowed`). The check is in the
  use cases because a database check cannot see the provider's kind and must not be stricter than the domain
  anyway.
- **Precedence.** For an OpenAI-compatible provider the user's price is the only source (the table holds none
  for that kind); for every other kind the table is the only source. A model with neither has an unknown cost.
  `AiMeter` looks the price up per metered call; a store failure reading it leaves the cost unknown (logged by
  kind only) and the answer is still returned, like any meter failure (ADR-0043).
- **No retroactive pricing.** The meter is append-only (ADR-0043). A price applies to calls recorded after it was
  set; entries recorded before keep their cost (unknown stays `NULL`, and `unknownCostCalls` keeps counting
  them), and removing a price does not change entries recorded under it. The cost reports and the budget cap
  read the same entries, so they stay consistent by construction: the cap counts the known costs of the month,
  which now includes priced calls from the moment of the price.
- **Who and how.** Only the user (`SetupRules`: AI, scanners and MCP clients must not hide their own spending
  from the cap). `PUT /api/setup/providers/{id}/model-prices` sets or replaces a price (the model name is in the
  body, since names contain slashes), `GET` lists, `DELETE ...?model=` removes. Every change writes one
  changelog entry on the **provider** (`ai_provider`, like capability corrections) with actor `User`, the model
  in the description and the old and new micros as field changes; setting the same price again writes nothing.
- **No confirmation step for removal.** Removing a price destroys no data the user cannot enter again and
  changes no past cost, like removing the monthly cap (which is a `PUT`, not a `DELETE`). The `DELETE` handler
  is therefore a reviewed entry in `ConfirmationRules.ENDPOINTS_WITHOUT_CONFIRMATION`, with this reason.
- **Export/import.** `ai_model_price_override` is in `BackupTables.EXPORTED` and covered by the round-trip test.

## Consequences

- A user can make an OpenRouter or local model count toward the cap, and a free local model shows as a known
  cost of 0 instead of "unknown".
- Changing a price mid-month leaves the month's totals mixed: calls before and after use different prices.
  That is the honest reading of an append-only meter; the UI says so next to the form.
- A model renamed by the provider needs its price entered again; a price for a model that is never called is
  harmless.
