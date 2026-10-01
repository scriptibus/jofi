// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { expectNoA11yViolations, onStack, snapshot } from "./helpers.ts";

// Settings > AI > Costs as it looks in every browser project (light, dark, phone). What other tests spent
// is not known here, so these only read: the structure, the 12-month history and a month without calls.
// A real call through the seeded provider shows up in tests/ai/ai-costs.spec.ts.
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

test("costs: the month, the 12-month history, and an earlier month without calls", async ({ page }) => {
  await page.goto("/settings");
  const costs = page.getByRole("region", { name: "AI costs", exact: true });
  const history = costs.getByRole("table", { name: "AI costs per month" });
  await expect(history).toBeVisible();
  await expect(history.getByRole("row")).toHaveCount(13);
  await expect(history.getByRole("columnheader", { name: "Cost" })).toBeVisible();
  await expect(costs.getByRole("heading", { level: 3, name: "Last 12 months" })).toBeVisible();
  // The wide tables scroll inside their card; the page itself never scrolls sideways (phone width).
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-ai-costs");

  // The oldest month of the history has no calls, and the picker offers no later month than the current one.
  await costs.getByRole("button", { name: /Month/ }).click();
  const options = page.getByRole("listbox", { name: "Month" }).getByRole("option");
  await expect(options).toHaveCount(12);
  await options.last().click();
  await expect(costs.getByText(/^No AI calls yet in .+\.$/)).toBeVisible();
  await expect(costs.getByRole("table", { name: "Costs by task" })).toHaveCount(0);
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-ai-costs-empty-month");
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("Kosten: Verlauf der letzten 12 Monate und ein Monat ohne Aufrufe", async ({ page }) => {
    await page.goto("/settings");
    const costs = page.getByRole("region", { name: "KI-Kosten", exact: true });
    await expect(costs.getByRole("table", { name: "KI-Kosten pro Monat" })).toBeVisible();
    await expect(costs.getByRole("heading", { level: 3, name: "Letzte 12 Monate" })).toBeVisible();
    await costs.getByRole("button", { name: /Monat/ }).click();
    await page.getByRole("listbox", { name: "Monat" }).getByRole("option").last().click();
    await expect(costs.getByText(/^Noch keine KI-Aufrufe im .+\.$/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "settings-ai-costs-de");
  });
});
