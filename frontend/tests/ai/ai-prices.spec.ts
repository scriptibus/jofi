// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Prices for the models of an OpenAI-compatible provider (#142, ADR-0055) against the real backend, in the
// `ai-setup` project (playwright.config.ts): add, change and remove a price on the seeded fake provider. The
// prices are the only state these tests touch; each test removes what it set.

import { devices, expect, type Locator, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";

test.describe.configure({ mode: "serial" });

const SEEDED_ID = "00000000-0000-4000-8000-0000000e2e01";
const SEEDED = "Fake AI (e2e)";
const MODEL = "e2e-price-model";

async function csrfHeaders(page: Page) {
  const cookie = (await page.context().cookies()).find((entry) => entry.name === "XSRF-TOKEN");
  if (cookie === undefined) throw new Error("no XSRF-TOKEN cookie");
  return { "X-XSRF-TOKEN": decodeURIComponent(cookie.value) };
}

/** Removes every price of the seeded provider, so the tests leave nothing behind. */
async function removePrices(page: Page) {
  await page.goto("/settings");
  const headers = await csrfHeaders(page);
  const listed = await page.request.get(`/api/setup/providers/${SEEDED_ID}/model-prices`);
  expect(listed.ok()).toBe(true);
  for (const price of (await listed.json()) as { model: string }[]) {
    const removed = await page.request.delete(
      `/api/setup/providers/${SEEDED_ID}/model-prices?model=${encodeURIComponent(price.model)}`,
      { headers },
    );
    expect(removed.status()).toBe(204);
  }
}

function pricesCard(page: Page, heading: string, provider: string): Locator {
  return page.getByRole("region", { name: heading }).getByRole("region", { name: provider });
}

test.afterEach(async ({ page }) => {
  await removePrices(page);
});

test("a price is added, changed and removed on the OpenAI-compatible provider", async ({ page }) => {
  await page.goto("/settings");
  const card = pricesCard(page, "Prices for your own models", `Prices of ${SEEDED}`);
  await expect(card.getByText("No prices set for this provider yet.")).toBeVisible();

  const add = card.getByRole("region", { name: "Add a price" });
  await add.getByLabel("Model name").fill(MODEL);
  await add.getByLabel("Input price per million tokens (US dollars)").fill("0.15");
  await add.getByLabel("Output price per million tokens (US dollars)").fill("0.6");
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-price-form");
  await add.getByRole("button", { name: "Save price" }).click();

  await expect(card.getByText(`Price of ${MODEL} saved.`)).toBeVisible();
  await expect(card.getByText("Input $0.15, output $0.60 per million tokens")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "ai-price-saved");
  const stored = await (await page.request.get(`/api/setup/providers/${SEEDED_ID}/model-prices`)).json();
  expect(stored).toMatchObject([
    { model: MODEL, inputMicrosPerMillion: 150_000, outputMicrosPerMillion: 600_000 },
  ]);

  // 0 is a price: a model on the user's own machine.
  await card.getByRole("button", { name: `Edit the price of ${MODEL}` }).click();
  const edit = card.getByRole("region", { name: `Change the price of ${MODEL}` });
  await expect(edit.getByLabel("Model name")).toHaveAttribute("readonly", "");
  await edit.getByLabel("Input price per million tokens (US dollars)").fill("0");
  await edit.getByLabel("Output price per million tokens (US dollars)").fill("0");
  await edit.getByRole("button", { name: "Save price" }).click();
  await expect(card.getByText("Input $0.00, output $0.00 per million tokens")).toBeVisible();

  await card.getByRole("button", { name: `Remove the price of ${MODEL}` }).click();
  await expect(card.getByText(`Price of ${MODEL} removed. Its next calls have no price.`)).toBeVisible();
  await expect(card.getByText("No prices set for this provider yet.")).toBeVisible();
  await snapshot(page, "ai-price-removed");
});

test("a price the server refuses is shown at its field", async ({ page }) => {
  await page.goto("/settings");
  const add = pricesCard(page, "Prices for your own models", `Prices of ${SEEDED}`).getByRole("region", {
    name: "Add a price",
  });
  await add.getByLabel("Model name").fill("m".repeat(201));
  await add.getByLabel("Input price per million tokens (US dollars)").fill("1");
  await add.getByLabel("Output price per million tokens (US dollars)").fill("1");
  await add.getByRole("button", { name: "Save price" }).click();

  await expect(add.getByLabel("Model name")).toHaveAttribute("aria-invalid", "true");
  await expect(add.getByText("This is too long.")).toBeVisible();
  await expectNoA11yViolations(page);
});

test.describe("in German, dark, at phone width", () => {
  const { defaultBrowserType: _browser, ...pixel } = devices["Pixel 7"];
  test.use({ ...pixel, locale: "de-DE", colorScheme: "dark", reducedMotion: "reduce" });

  test("Preise: hinzufügen mit Dezimalkomma, entfernen", async ({ page }) => {
    await page.goto("/settings");
    const card = pricesCard(page, "Preise für eigene Modelle", `Preise von ${SEEDED}`);
    const add = card.getByRole("region", { name: "Preis hinzufügen" });
    await add.getByLabel("Modellname").fill(MODEL);
    await add.getByLabel("Eingabepreis pro Million Token (US-Dollar)").fill("0,125");
    await add.getByLabel("Ausgabepreis pro Million Token (US-Dollar)").fill("0,5");
    await add.getByRole("button", { name: "Preis speichern" }).click();

    await expect(card.getByText(`Preis von ${MODEL} gespeichert.`)).toBeVisible();
    await expect(card.getByText(/Eingabe 0,125\s\$, Ausgabe 0,50\s\$ pro Million Token/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "ai-price-de");

    await card.getByRole("button", { name: `Preis von ${MODEL} entfernen` }).click();
    await expect(card.getByText(`Preis von ${MODEL} entfernt.`)).toBeVisible();
    await expectNoA11yViolations(page);
  });
});
