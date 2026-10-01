// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Settings > AI > Costs against the real backend and the fake AI provider, in the `ai-costs` project, after
// `ai-setup` (playwright.config.ts): a posting import makes one EXTRACTION call through the seeded provider, and the
// month's costs show it per task, provider and model. Other tests spend on the same instance, so the
// assertions work from the API's own figures before and after, never from absolute counts.

import { devices, expect, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";

test.describe.configure({ mode: "serial" });

interface Totals {
  calls: number;
  unknownCostCalls: number;
}

interface CostSummary {
  total: Totals;
  byTask: { task: string; totals: Totals }[];
  byModel: { model: string; providerKind: string; totals: Totals }[];
}

async function summary(page: Page): Promise<CostSummary> {
  const response = await page.request.get("/api/setup/costs");
  expect(response.status()).toBe(200);
  return (await response.json()) as CostSummary;
}

const callsOf = (costs: CostSummary, task: string) =>
  costs.byTask.find((line) => line.task === task)?.totals.calls ?? 0;

/** Makes one EXTRACTION call through the seeded fake provider: the worker reads a pasted posting. */
async function extractOnce(page: Page): Promise<CostSummary> {
  await page.goto("/settings");
  const before = callsOf(await summary(page), "EXTRACTION");
  const cookie = (await page.context().cookies()).find((entry) => entry.name === "XSRF-TOKEN");
  if (cookie === undefined) throw new Error("no XSRF-TOKEN cookie");
  const started = await page.request.post("/api/applications/imports/text", {
    data: { description: `Senior Kotlin Developer at Costs Fixture GmbH, Berlin. ref ${Date.now()}` },
    headers: { "X-XSRF-TOKEN": decodeURIComponent(cookie.value) },
  });
  expect(started.status()).toBe(202);
  await expect
    .poll(async () => callsOf(await summary(page), "EXTRACTION"), { timeout: 60_000 })
    .toBeGreaterThan(before);
  return summary(page);
}

test("a call through the seeded provider shows up in the month's costs per task, provider and model", async ({
  page,
}) => {
  const costs = await extractOnce(page);
  const calls = callsOf(costs, "EXTRACTION");
  const model = costs.byModel.find((line) => line.model === "fake-extraction");
  expect(model?.providerKind).toBe("OPENAI_COMPATIBLE");

  await page.goto("/settings");
  const region = page.getByRole("region", { name: "AI costs", exact: true });
  const tasks = region.getByRole("table", { name: "Costs by task" });
  const extraction = tasks.getByRole("row").filter({ hasText: "Extraction from postings and documents" });
  await expect(extraction.getByRole("cell").nth(1)).toHaveText(calls.toLocaleString("en-US"));
  // The fake model has no price: counted as calls without a price, never as $0.
  await expect(extraction.getByText(/calls? without a price/)).toBeVisible();
  await expect(extraction).not.toContainText("$0");
  const providers = region.getByRole("table", { name: "Costs by provider" });
  await expect(providers.getByRole("row").filter({ hasText: "OpenAI-compatible endpoint" })).toBeVisible();
  const models = region.getByRole("table", { name: "Costs by model" });
  await expect(models.getByRole("row").filter({ hasText: "fake-extraction" })).toBeVisible();
  // The current month is also the newest row of the history.
  const history = region.getByRole("table", { name: "AI costs per month" });
  await expect(history.getByRole("row").nth(1).getByRole("cell").nth(1)).toHaveText(
    costs.total.calls.toLocaleString("en-US"),
  );
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-costs-with-calls");
});

test.describe("in German, dark, at phone width", () => {
  const { defaultBrowserType: _browser, ...pixel } = devices["Pixel 7"];
  test.use({ ...pixel, locale: "de-DE", colorScheme: "dark", reducedMotion: "reduce" });

  test("Kosten: ein Aufruf über den Seed-Anbieter erscheint je Aufgabe, Anbieter und Modell", async ({
    page,
  }) => {
    const costs = await extractOnce(page);
    const calls = callsOf(costs, "EXTRACTION");

    await page.goto("/settings");
    const region = page.getByRole("region", { name: "KI-Kosten", exact: true });
    const tasks = region.getByRole("table", { name: "Kosten nach Aufgabe" });
    const extraction = tasks.getByRole("row").filter({ hasText: "Auslesen von Stellen und Dokumenten" });
    await expect(extraction.getByRole("cell").nth(1)).toHaveText(calls.toLocaleString("de-DE"));
    await expect(extraction.getByText(/Aufrufe? ohne Preis/)).toBeVisible();
    await expect(
      region
        .getByRole("table", { name: "Kosten nach Anbieter" })
        .getByRole("row")
        .filter({ hasText: "OpenAI-kompatibler Endpunkt" }),
    ).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "ai-costs-with-calls-de");
  });
});
