-- SPDX-FileCopyrightText: 2026 Jofi contributors
-- SPDX-License-Identifier: AGPL-3.0-or-later

-- The AI gateway (#20, ADR-0043) meters every call, also when its cost is unknown: a model without a
-- list price in the price table (every OpenAI-compatible endpoint until the user sets a price, #24),
-- or a stream cancelled before the provider reported usage. Such an entry keeps its tokens and has
-- NULL as its cost; the gateway never guesses a price. Budget sums skip NULL (SUM ignores it).
ALTER TABLE ai_cost_entry ALTER COLUMN cost_micros DROP NOT NULL;
