// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The AI setup against the real backend and the fake AI provider, in the `ai-setup` project
// (playwright.config.ts): adding a provider, the connection test, task models with capability warnings,
// the budget cap and the confirmed delete. They change the one AI setup every other test sees, so they run
// alone, one at a time, after the browser projects; each test puts the seeded setup back.

import { type APIRequestContext, devices, expect, type Locator, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";

test.describe.configure({ mode: "serial" });

const SEEDED_ID = "00000000-0000-4000-8000-0000000e2e01";
const SEEDED = "Fake AI (e2e)";
const FAKE_AI = "http://fake-ai:8080/v1";
const PREFIX = "E2E ";

/** The API as the logged-in browser, with the CSRF header the backend wants (ADR-0035). */
async function csrfHeaders(page: Page) {
  const cookie = (await page.context().cookies()).find((entry) => entry.name === "XSRF-TOKEN");
  if (cookie === undefined) throw new Error("no XSRF-TOKEN cookie");
  return { "X-XSRF-TOKEN": decodeURIComponent(cookie.value) };
}

/** Puts the seeded setup back: no cap, every text task on the seeded model, no provider of ours left. */
async function restoreSeededSetup(page: Page) {
  await page.goto("/settings");
  const headers = await csrfHeaders(page);
  const request: APIRequestContext = page.request;
  expect((await request.put("/api/setup/budget", { data: { capMicros: null }, headers })).ok()).toBe(true);
  for (const task of ["CLASSIFICATION"]) {
    const model = `fake-${task.toLowerCase().replaceAll("_", "-")}`;
    const response = await request.put(`/api/setup/assignments/${task}`, {
      data: { providerId: SEEDED_ID, model },
      headers,
    });
    expect(response.ok()).toBe(true);
  }
  const providers = (await (await request.get("/api/setup/providers")).json()) as {
    id: string;
    displayName: string;
  }[];
  for (const provider of providers.filter((entry) => entry.displayName.startsWith(PREFIX))) {
    const first = await request.delete(`/api/setup/providers/${provider.id}`, { headers });
    const { confirmationToken } = (await first.json()) as { confirmationToken: string };
    const confirmed = await request.delete(`/api/setup/providers/${provider.id}`, {
      headers: { ...headers, "Jofi-Confirmation": confirmationToken },
    });
    expect(confirmed.status()).toBe(204);
  }
}

/** Picks a provider's model in one of our Selects (the same model names exist at both fake providers). */
async function pickModel(page: Page, task: string, provider: string, model: string) {
  await page.getByRole("button", { name: new RegExp(`${task}$`) }).click();
  const list = page.getByRole("listbox", { name: task });
  await list.getByRole("group", { name: provider }).getByRole("option", { name: model }).click();
  await expect(list).toBeHidden();
}

function card(page: Page, name: string): Locator {
  return page.getByRole("article", { name });
}

test.afterEach(async ({ page }) => {
  await restoreSeededSetup(page);
});

test("the guide adds a provider, tests it, warns about a weak model, sets a budget; the provider is removed after confirmation", async ({
  page,
}) => {
  const name = `${PREFIX}second fake`;
  await page.goto("/setup?step=providers");
  await page.getByRole("button", { name: "Add another provider" }).click();
  await page.getByRole("radiogroup", { name: "Provider" }).getByText("OpenAI-compatible endpoint").click();
  await expect(page.getByRole("region", { name: "Privacy with OpenAI-compatible endpoint" })).toBeVisible();
  await page.getByLabel("Name in Jofi").fill(name);
  await page.getByLabel("Base URL").fill(FAKE_AI);
  await expect(page.getByText("This connection is not encrypted")).toBeVisible();
  await page.getByLabel("API key").fill("e2e-not-a-real-key");
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-provider-form");
  await page.getByRole("button", { name: "Add provider" }).click();

  const added = card(page, name);
  await expect(added.getByText(/API key stored/)).toBeVisible();
  await expect(page.getByText("e2e-not-a-real-key")).toHaveCount(0);
  await added.getByRole("button", { name: "Test connection" }).click();
  await expect(added.getByText(/^Connected\. The provider lists \d+ models\.$/)).toBeVisible();
  await snapshot(page, "ai-provider-tested");

  // A new base URL on another server needs the key again.
  await added.getByRole("button", { name: "Edit" }).click();
  await added.getByLabel("Base URL").fill("http://127.0.0.1:8080/v1");
  await added.getByRole("button", { name: "Save changes" }).click();
  await expect(added.getByText(/points to another server\. Enter the API key again/).first()).toBeVisible();
  await snapshot(page, "ai-provider-key-reentry");
  await added.getByRole("button", { name: "Cancel" }).click();

  // The new provider's models have no known capabilities: the task gets a warning.
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Models per task" })).toBeVisible();
  await pickModel(page, "Classification", name, "fake-classification");
  const warning = page.getByRole("note").filter({ hasText: "This model may not be up to it" });
  await expect(warning).toContainText("a context of at least 8,192 tokens");
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-capability-warning");

  // While a task uses it, the provider cannot be removed.
  await page.goto("/setup?step=providers");
  await card(page, name).getByRole("button", { name: "Remove" }).click();
  await expect(card(page, name).getByText(/Tasks still use this provider/)).toBeVisible();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);

  // Budget: set a cap, then remove it.
  await page.goto("/setup?step=budget");
  await page.getByLabel("Monthly cap in US dollars").fill("12.5");
  await page.getByRole("button", { name: "Save cap" }).click();
  await expect(page.getByText("Monthly cap set to $12.50.")).toBeVisible();
  await expect(page.getByText(/of \$12\.50/)).toBeVisible();
  await snapshot(page, "ai-budget-set");
  await page.getByRole("button", { name: "Remove cap" }).click();
  await expect(page.getByText("The monthly cap is removed.")).toBeVisible();

  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("region", { name: "All set" })).toContainText(name);
  await snapshot(page, "ai-summary");

  // Give the task back, then remove the provider with the server's confirmation.
  await page.goto("/settings");
  await pickModel(page, "Classification", SEEDED, "fake-classification");
  await expect(page.getByRole("note").filter({ hasText: "This model may not be up to it" })).toHaveCount(0);
  await card(page, name).getByRole("button", { name: "Remove" }).click();
  const dialog = page.getByRole("alertdialog", { name: "Remove this provider?" });
  await expect(dialog).toContainText(`Jofi removes "${name}"`);
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-provider-delete-confirm");
  await dialog.getByRole("button", { name: "Remove provider" }).click();
  await expect(card(page, name)).toHaveCount(0);
});

test.describe("in German, dark, at phone width", () => {
  const { defaultBrowserType: _browser, ...pixel } = devices["Pixel 7"];
  test.use({ ...pixel, locale: "de-DE", colorScheme: "dark", reducedMotion: "reduce" });

  test("Einrichtung: Anbieter anlegen, Budget in Euro-Schreibweise, löschen mit Bestätigung", async ({
    page,
  }) => {
    const name = `${PREFIX}Mistral`;
    await page.goto("/setup?step=providers");
    await page.getByRole("button", { name: "Weiteren Anbieter hinzufügen" }).click();
    await page.getByRole("radiogroup", { name: "Anbieter" }).getByText("Mistral", { exact: true }).click();
    await expect(page.getByRole("region", { name: "Datenschutz bei Mistral" })).toBeVisible();
    await page.getByLabel("Name in Jofi").fill(name);
    await page.getByRole("button", { name: "Anbieter hinzufügen" }).click();
    await expect(page.getByText("Gib den API-Schlüssel ein.")).toBeVisible();
    await page.getByLabel("API-Schlüssel").fill("e2e-not-a-real-key");
    await page.getByRole("button", { name: "Anbieter hinzufügen" }).click();
    await expect(card(page, name).getByText(/API-Schlüssel gespeichert/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "ai-provider-added-de");

    await page.goto("/setup?step=budget");
    await page.getByLabel("Monatliche Obergrenze in US-Dollar").fill("7,5");
    await page.getByRole("button", { name: "Obergrenze speichern" }).click();
    await expect(page.getByText(/Monatliche Obergrenze auf 7,50\s\$ gesetzt\./)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "ai-budget-de");
    await page.getByRole("button", { name: "Obergrenze entfernen" }).click();
    await expect(page.getByText("Die monatliche Obergrenze ist entfernt.")).toBeVisible();

    await page.goto("/settings");
    await card(page, name).getByRole("button", { name: "Entfernen" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Diesen Anbieter entfernen?" });
    await expect(dialog).toContainText(`Jofi entfernt „${name}“`);
    await snapshot(page, "ai-provider-delete-de");
    await dialog.getByRole("button", { name: "Abbrechen" }).click();
    await expect(card(page, name)).toBeVisible();
    await card(page, name).getByRole("button", { name: "Entfernen" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Anbieter entfernen" }).click();
    await expect(card(page, name)).toHaveCount(0);
  });
});
