// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setLocale } from "../../paraglide/runtime.js";
import {
  type Breakdown,
  type FakeSetupState,
  fakeSetupBackend,
  NO_CALLS,
  totals,
} from "../../test/fakeSetupBackend";
import { CostsCard } from "./CostsCard";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  setLocale("en", { reload: false });
});
afterAll(() => server.close());

/** A month with calls: 3 extractions (priced) and 2 chat calls on a model without a price. */
const SEPTEMBER: Breakdown = {
  total: totals(5, 3_200_000, 2),
  byTask: [
    { task: "EXTRACTION", totals: totals(3, 3_200_000) },
    { task: "CHAT", totals: totals(2, 0, 2) },
  ],
  byProviderKind: [
    { providerKind: "ANTHROPIC", totals: totals(3, 3_200_000) },
    { providerKind: "OPENAI_COMPATIBLE", totals: totals(2, 0, 2) },
  ],
  byModel: [
    { providerKind: "ANTHROPIC", model: "claude-haiku", totals: totals(3, 3_200_000) },
    { providerKind: "OPENAI_COMPATIBLE", model: "llama-home", totals: totals(2, 0, 2) },
  ],
};

function start(setup: Partial<FakeSetupState> = {}) {
  const fake = fakeSetupBackend(setup);
  server.use(...fake.handlers);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <CostsCard />
    </QueryClientProvider>,
  );
  return { setup: fake.state, user: userEvent.setup() };
}

const table = (name: string) => screen.findByRole("table", { name });
const rowOf = (within_: HTMLElement, text: string) => {
  const row = within(within_)
    .getAllByRole("row")
    .find((candidate) => candidate.textContent?.includes(text));
  if (!row) throw new Error(`no row with ${text}`);
  return row;
};

describe("Costs card: the month's costs", () => {
  it("shows a loading state before the figures arrive", async () => {
    start();
    expect(screen.getAllByRole("status")[0]).toHaveTextContent("Loading the costs…");
    expect(await screen.findByText("No AI calls yet in September 2026.")).toBeVisible();
  });

  it("shows the spending against the cap as text and as a progress bar", async () => {
    start({ costs: { "2026-09": SEPTEMBER }, capMicros: 10_000_000, spentMicros: 3_200_000 });
    const bar = await screen.findByRole("progressbar", { name: "Spent against the monthly cap" });
    expect(bar).toHaveAttribute("aria-valuenow", "32");
    expect(bar).toHaveAttribute("aria-valuetext", "$3.20 of $10.00 (32%)");
    expect(screen.getAllByText("$3.20 of $10.00 (32%)").length).toBeGreaterThan(0);
    expect(screen.queryByText("Budget reached")).toBeNull();
    expect(screen.getByText("5 calls with 500 input and 100 output tokens")).toBeVisible();
  });

  it("says that the cap is reached in words, with a full bar", async () => {
    start({ costs: { "2026-09": SEPTEMBER }, capMicros: 3_200_000, spentMicros: 3_200_000 });
    expect(await screen.findByText("Budget reached")).toBeVisible();
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
  });

  it("shows only the sum when no cap is set", async () => {
    start({ costs: { "2026-09": SEPTEMBER }, capMicros: null });
    expect(await screen.findByText("Spent in September 2026: $3.20")).toBeVisible();
    expect(screen.getByText("No monthly cap is set.")).toBeVisible();
    expect(screen.queryByRole("progressbar")).toBeNull();
  });

  it("breaks the costs down per task, provider kind and model", async () => {
    start({ costs: { "2026-09": SEPTEMBER } });
    const tasks = await table("Costs by task");
    expect(
      within(tasks)
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["Task", "Calls", "Input tokens", "Output tokens", "Cost"]);
    const extraction = rowOf(tasks, "Extraction");
    expect(
      within(extraction)
        .getAllByRole("cell")
        .map((cell) => cell.textContent),
    ).toEqual(["Extraction from postings and documents", "3", "300", "60", "$3.20"]);
    expect(within(await table("Costs by provider")).getByText("Anthropic Claude")).toBeVisible();
    const models = await table("Costs by model");
    expect(within(rowOf(models, "claude-haiku")).getByText("Anthropic Claude")).toBeVisible();
  });

  it("counts calls without a price and never shows them as $0", async () => {
    start({ costs: { "2026-09": SEPTEMBER } });
    const tasks = await table("Costs by task");
    const chat = rowOf(tasks, "Chat");
    expect(within(chat).getByText("No price")).toBeVisible();
    expect(within(chat).getByText("2 calls without a price")).toBeVisible();
    expect(chat).not.toHaveTextContent("$0");
    // The month's total mixes priced and unpriced calls: the sum counts the priced ones only.
    expect(screen.getByText("2 calls without a price", { selector: "p" })).toBeVisible();
  });

  it("uses the singular for one call, and keeps the sum of the priced calls next to the unpriced ones", async () => {
    const mixed: Breakdown = {
      total: totals(3, 1_200_000, 1),
      byTask: [{ task: "EXTRACTION", totals: totals(3, 1_200_000, 1) }],
      byProviderKind: [{ providerKind: "OPENAI", totals: totals(3, 1_200_000, 1) }],
      byModel: [{ providerKind: "OPENAI", model: "gpt-x", totals: totals(3, 1_200_000, 1) }],
    };
    start({ costs: { "2026-09": mixed } });
    const extraction = rowOf(await table("Costs by task"), "Extraction");
    expect(within(extraction).getByText("$1.20")).toBeVisible();
    expect(within(extraction).getByText("1 call without a price")).toBeVisible();
  });

  it("shows a calm empty state without tables when no AI call was made yet", async () => {
    start();
    expect(await screen.findByText("No AI calls yet in September 2026.")).toBeVisible();
    expect(screen.queryByRole("table", { name: "Costs by task" })).toBeNull();
    expect(screen.queryByText(/\$0\.00 of/)).toBeNull();
  });
});

describe("Costs card: history", () => {
  it("lists the last 12 months as a table, newest first, empty months as zero calls", async () => {
    start({ costs: { "2026-09": SEPTEMBER, "2026-07": { ...SEPTEMBER, total: totals(1, 10_000) } } });
    expect(await screen.findByRole("heading", { name: "Last 12 months" })).toBeVisible();
    const history = await table("AI costs per month");
    const rows = within(history).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(12);
    expect(rows[0]).toHaveTextContent("September 2026");
    expect(rows[0]).toHaveTextContent("$3.20");
    expect(rows[2]).toHaveTextContent("July 2026");
    expect(rows[2]).toHaveTextContent("$0.01");
    expect(rows[11]).toHaveTextContent("October 2025");
    expect(rows[1]).toHaveTextContent("August 2026");
    expect(within(rows[1] as HTMLElement).getAllByRole("cell")[1]).toHaveTextContent("0");
  });

  it("asks the API for exactly 12 months", async () => {
    const { setup } = start();
    await table("AI costs per month");
    expect(setup.costCalls).toContain("history:12");
  });
});

describe("Costs card: month picker", () => {
  it("offers the current month back to the first month of the history, never a later one", async () => {
    const { user } = start();
    await user.click(await screen.findByRole("button", { name: /Month/ }));
    const options = within(screen.getByRole("listbox", { name: "Month" })).getAllByRole("option");
    expect(options).toHaveLength(12);
    expect(options[0]).toHaveTextContent("September 2026");
    expect(options[11]).toHaveTextContent("October 2025");
    expect(screen.queryByRole("option", { name: "October 2026" })).toBeNull();
  });

  it("shows an earlier month without the cap, and the current one again with it", async () => {
    const august: Breakdown = { ...SEPTEMBER, total: totals(5, 4_000_000, 2) };
    const { user, setup } = start({
      costs: { "2026-09": SEPTEMBER, "2026-08": august },
      capMicros: 10_000_000,
      spentMicros: 3_200_000,
    });
    expect(await screen.findByRole("progressbar")).toBeVisible();
    await user.click(screen.getByRole("button", { name: /Month/ }));
    await user.click(screen.getByRole("option", { name: "August 2026" }));
    expect(await screen.findByText("Spent in August 2026: $4.00")).toBeVisible();
    expect(screen.queryByRole("progressbar")).toBeNull();
    expect(screen.queryByText("No monthly cap is set.")).toBeNull();
    expect(setup.costCalls).toContain("summary:2026-08");

    await user.click(screen.getByRole("button", { name: /Month/ }));
    await user.click(screen.getByRole("option", { name: "September 2026" }));
    expect(await screen.findByRole("progressbar")).toBeVisible();
    expect(setup.costCalls.at(-1)).toBe("summary:-");
  });

  it("shows a 400 invalid-input on the month at the picker, not as a general failure", async () => {
    const { user } = start();
    await screen.findByRole("button", { name: /Month/ });
    server.use(
      http.get(`${window.location.origin}/api/setup/costs`, () =>
        HttpResponse.json(
          {
            type: "urn:jofi:problem:setup:invalid-input",
            title: "Invalid",
            status: 400,
            violations: [{ field: "month", problem: "OUT_OF_RANGE" }],
          },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    await user.click(screen.getByRole("button", { name: /Month/ }));
    await user.click(screen.getByRole("option", { name: "August 2026" }));
    const picker = await screen.findByRole("button", { name: /Month/ });
    expect(await screen.findByText("This value is out of range.")).toBeVisible();
    expect(picker).toHaveAccessibleDescription("This value is out of range.");
    expect(screen.queryByText("The costs could not be loaded.")).toBeNull();
  });
});

describe("Costs card: errors", () => {
  it("shows a failed month with a retry that loads it", async () => {
    const { setup, user } = start({ costs: { "2026-09": SEPTEMBER }, costsFail: { summary: 500 } });
    expect(await screen.findByText("The costs could not be loaded.")).toBeVisible();
    expect(screen.getByRole("alert")).toBeVisible();
    // The history is independent and still there.
    expect(await table("AI costs per month")).toBeVisible();
    setup.costsFail = {};
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await table("Costs by task")).toBeVisible();
    expect(screen.queryByText("The costs could not be loaded.")).toBeNull();
  });

  it("shows a failed history with a retry, and hides the picker until it is there", async () => {
    const { setup, user } = start({ costs: { "2026-09": SEPTEMBER }, costsFail: { history: 500 } });
    expect(await screen.findByText("The monthly history could not be loaded.")).toBeVisible();
    expect(screen.queryByRole("button", { name: /Month/ })).toBeNull();
    expect(await table("Costs by task")).toBeVisible();
    setup.costsFail = {};
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await table("AI costs per month")).toBeVisible();
    expect(screen.getByRole("button", { name: /Month/ })).toBeVisible();
  });
});

describe("Costs card in German", () => {
  it("formats money, months and plurals for the locale", async () => {
    setLocale("de", { reload: false });
    start({
      costs: { "2026-09": { ...SEPTEMBER, total: totals(1_234, 1_234_500_000, 1) } },
      capMicros: 2_000_000_000,
      spentMicros: 1_234_500_000,
    });
    const bar = await screen.findByRole("progressbar", { name: "Ausgaben gegenüber der Monatsobergrenze" });
    expect(bar.getAttribute("aria-valuetext")?.replace(/\s/g, " ")).toBe("1.234,50 $ von 2.000,00 $ (61 %)");
    expect(screen.getByText("1 Aufruf ohne Preis", { selector: "p" })).toBeVisible();
    expect(screen.getByText(/^1\.234 Aufrufe mit/)).toBeVisible();
    expect(await table("Kosten nach Aufgabe")).toBeVisible();
    expect(await table("KI-Kosten pro Monat")).toHaveTextContent("September 2026");
  });

  it("shows the empty state in German", async () => {
    setLocale("de", { reload: false });
    start({ costs: { "2026-09": { ...SEPTEMBER, total: NO_CALLS } } });
    expect(await screen.findByText("Noch keine KI-Aufrufe im September 2026.")).toBeVisible();
  });
});
