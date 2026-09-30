// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { AxeBuilder } from "@axe-core/playwright";
import { expect, type Page, test } from "@playwright/test";

const WCAG_22_AA = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

/** True when running against the full compose stack (`pnpm e2e`), false under `vite preview`. */
export const onStack = Boolean(process.env.JOFI_E2E_BASE_URL);

export async function expectNoA11yViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(WCAG_22_AA).analyze();
  expect(results.violations).toEqual([]);
}

/** Optional screenshots for PRs / agent review: set SCREENSHOT_DIR to collect them. */
export async function snapshot(page: Page, name: string) {
  const dir = process.env.SCREENSHOT_DIR;
  if (dir) await page.screenshot({ path: `${dir}/${test.info().project.name}-${name}.png`, fullPage: true });
}

/**
 * Picks an option in one of our SegmentedControls the way a user does: by clicking its
 * visible label (the native radio input is visually hidden), then asserts the radio state.
 */
export async function choose(page: Page, group: string, option: string) {
  const radiogroup = page.getByRole("radiogroup", { name: group });
  await radiogroup.getByText(option, { exact: true }).click();
  await expect(radiogroup.getByRole("radio", { name: option })).toBeChecked();
}

/** The main navigation (sidebar on desktop, bottom tab bar on phones). */
export function mainNav(page: Page) {
  return page.getByRole("navigation", { name: /^(Main|Hauptnavigation)$/ });
}
