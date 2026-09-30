// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { expectNoA11yViolations, onStack, snapshot } from "./helpers.ts";

// Settings > AI and the setup guide as they look in every browser project (light, dark, phone), on the
// seeded fake AI provider. Nothing here changes the AI setup: every project runs in parallel on one stack,
// so adding, assigning, budgeting and deleting run alone in the `ai-setup` project (tests/ai).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const SEEDED = "Fake AI (e2e)";

test("settings: providers, models per task and the budget", async ({ page }) => {
  await page.goto("/settings");
  const providers = page.getByRole("region", { name: "AI providers" });
  const seeded = providers.getByRole("article", { name: SEEDED });
  await expect(seeded.getByText("http://fake-ai:8080/v1")).toBeVisible();
  await expect(seeded.getByText(/No API key/)).toBeVisible();
  await expect(providers.getByRole("button", { name: "Add another provider" })).toBeVisible();

  const tasks = page.getByRole("region", { name: "Models per task" });
  await expect(tasks.getByRole("heading", { name: "Quick and cheap" })).toBeVisible();
  await expect(tasks.getByRole("button", { name: /fake-chat.*Chat$/ })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Monthly AI budget" }).getByText(/Spent this month/),
  ).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-ai");

  await tasks.getByRole("button", { name: /Chat$/ }).click();
  await expect(
    page.getByRole("listbox", { name: "Chat" }).getByRole("option", { name: "fake-chat" }),
  ).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-ai-model-list");
  await page.keyboard.press("Escape");
});

test("setup guide: every step reads well, and skipping leads to the dashboard", async ({ page }) => {
  await page.goto("/settings");
  await page.getByRole("button", { name: "Open the setup guide" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Set up AI" })).toBeVisible();
  const steps = page.getByRole("navigation", { name: "Setup steps" });
  await expect(steps.locator("[aria-current=step]")).toHaveText("1. Welcome");
  await expectNoA11yViolations(page);
  await snapshot(page, "wizard-welcome");

  await page.getByRole("button", { name: "Let's start" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Choose a provider" })).toBeVisible();
  await expect(page.getByRole("article", { name: SEEDED })).toBeVisible();
  await page.getByRole("button", { name: "Add another provider" }).click();
  await expect(page.getByRole("region", { name: "Privacy with Anthropic Claude" })).toBeVisible();
  await expect(page.getByLabel("API key")).toHaveAttribute("type", "password");
  await expectNoA11yViolations(page);
  await snapshot(page, "wizard-providers");

  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Models per task" })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "wizard-tasks");

  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByLabel("Monthly cap in US dollars")).toBeVisible();
  await expectNoA11yViolations(page);

  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("region", { name: "All set" })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "wizard-summary");

  await page.getByRole("button", { name: "Back" }).click();
  await page.getByRole("button", { name: "Skip for now" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
  ).toBeVisible();
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("setup guide and settings in German", async ({ page }) => {
    await page.goto("/setup?step=providers");
    await expect(page.getByRole("heading", { level: 1, name: "KI einrichten" })).toBeVisible();
    await expect(page.getByText("Schritt 2 von 5")).toBeVisible();
    await page.getByRole("button", { name: "Weiteren Anbieter hinzufügen" }).click();
    await expect(page.getByRole("note").filter({ hasText: "Prüfe die Bedingungen selbst" })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "wizard-providers-de");

    await page.goto("/settings");
    await expect(page.getByRole("region", { name: "Modelle je Aufgabe" })).toBeVisible();
    await expect(
      page.getByRole("region", { name: "Monatliches KI-Budget" }).getByText(/Diesen Monat ausgegeben/),
    ).toBeVisible();
    await expectNoA11yViolations(page);
  });
});
