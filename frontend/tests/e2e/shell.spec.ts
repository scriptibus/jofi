// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { AxeBuilder } from "@axe-core/playwright";
import { expect, type Page, test } from "@playwright/test";

const WCAG_22_AA = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

/**
 * Picks an option in one of our SegmentedControls the way a user does: by clicking its
 * visible label (the native radio input is visually hidden), then asserts the radio state.
 */
async function choose(page: Page, group: string, option: string) {
  const radiogroup = page.getByRole("radiogroup", { name: group });
  await radiogroup.getByText(option, { exact: true }).click();
  await expect(radiogroup.getByRole("radio", { name: option })).toBeChecked();
}

async function expectNoA11yViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(WCAG_22_AA).analyze();
  expect(results.violations).toEqual([]);
}

/** Optional screenshots for PRs / agent review: set SCREENSHOT_DIR to collect them. */
async function snapshot(page: Page, name: string) {
  const dir = process.env.SCREENSHOT_DIR;
  if (dir) await page.screenshot({ path: `${dir}/${test.info().project.name}-${name}.png`, fullPage: true });
}

test("shell: heading, theme, accent, language, accessibility", async ({ page }, testInfo) => {
  await page.goto("/");
  const html = page.locator("html");

  await expect(
    page.getByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
  ).toBeVisible();
  await expect(html).toHaveAttribute("lang", "en");
  await expectNoA11yViolations(page);
  await snapshot(page, "initial");

  // Theme: force the opposite of the emulated OS scheme.
  const osDark = testInfo.project.use.colorScheme === "dark";
  await choose(page, "Theme", osDark ? "Light" : "Dark");
  await expect(html).toHaveAttribute("data-theme", osDark ? "light" : "dark");
  await expectNoA11yViolations(page);

  // Accent preset.
  await choose(page, "Accent", "Teal");
  await expect(html).toHaveAttribute("data-accent", "teal");
  await expectNoA11yViolations(page);
  await snapshot(page, "themed");

  // Preferences survive a reload.
  await page.reload();
  await expect(html).toHaveAttribute("data-theme", osDark ? "light" : "dark");
  await expect(html).toHaveAttribute("data-accent", "teal");
  await expect(
    page.getByRole("radiogroup", { name: "Accent" }).getByRole("radio", { name: "Teal" }),
  ).toBeChecked();

  // Language: Paraglide stores the choice and reloads the document.
  await page.getByRole("radiogroup", { name: "Language" }).getByText("Deutsch", { exact: true }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "Lass den Esel die Eselsarbeit machen." }),
  ).toBeVisible();
  await expect(html).toHaveAttribute("lang", "de");
  await expect(page.getByRole("radiogroup", { name: "Farbschema" })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "de");
});

test("loader button toggles the working donkey", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Start working" }).click();
  await expect(page.getByText("Jofi is working…")).toBeVisible();
  await page.getByRole("button", { name: "Take a break" }).click();
  await expect(page.getByText("Jofi is resting.")).toBeVisible();
});
