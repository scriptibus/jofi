// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, onStack, snapshot } from "./helpers.ts";

// The Description tab (spec §6.1, ADR-0046, #105) against the real backend: recording texts, comparing
// versions and the version frozen when applying. Sources cannot be added through the API yet, so each project,
// language and attempt uses its own seeded application with a link and an offline scanner find
// (tests/stack/seed/db/0004-description-history.sql).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

const PROJECT_DIGITS: Record<string, string> = { "desktop-light": "1", "desktop-dark": "2", phone: "3" };

/** The seeded application of this project, language and attempt: a retry starts on a fresh one. */
function seededApplication(language: "en" | "de"): string {
  const { project, retry } = test.info();
  const projectDigit = PROJECT_DIGITS[project.name];
  if (projectDigit === undefined) throw new Error(`no seeded application for project ${project.name}`);
  const languageDigit = language === "en" ? "1" : "2";
  return `00000000-0000-4000-8000-00e2e105${projectDigit}${languageDigit}${Math.min(retry, 1)}0`;
}

async function record(page: Page, labels: { field: string; action: string }, text: string) {
  await page.getByLabel(labels.field).fill(text);
  await page.getByRole("button", { name: labels.action, exact: true }).click();
}

const EN = { field: "Current text of the posting", action: "Record text" };
const FIRST_TEXT = [
  "Platform Engineer (m/w/d)",
  "You run our Kubernetes clusters.",
  "<script>window.__descriptionXss = true</script> **not bold**",
  "Apply by October.",
].join("\n");
const SECOND_TEXT = FIRST_TEXT.replace("You run our Kubernetes clusters.", "You run our Nomad clusters.");
const THIRD_TEXT = `${SECOND_TEXT}\nRemote within Germany.`;

test("record texts, compare versions and see the version frozen when applying", async ({ page }) => {
  await page.goto(`/applications/${seededApplication("en")}?tab=description`);
  await expect(page.getByRole("tab", { name: "Description" })).toHaveAttribute("aria-selected", "true");
  const versions = page.getByRole("region", { name: "Versions", exact: true });
  await expect(versions.getByText(/No version of this source is saved yet/)).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "description-empty");

  await page.getByRole("button", { name: "Record the current text" }).click();
  await record(page, EN, FIRST_TEXT);
  await expect(page.getByRole("status").filter({ hasText: "New version saved: version 1." })).toBeVisible();
  const text = page.getByRole("region", { name: "Text of version 1" });
  // Plain text: the script and the Markdown show as typed and do nothing.
  await expect(text.getByText("<script>window.__descriptionXss = true</script> **not bold**")).toBeVisible();
  await expect(text.locator("script, strong")).toHaveCount(0);
  expect(
    await page.evaluate(() => (window as { __descriptionXss?: boolean }).__descriptionXss),
  ).toBeUndefined();

  await record(page, EN, FIRST_TEXT);
  await expect(page.getByRole("status").filter({ hasText: /^Unchanged: this text matches/ })).toBeVisible();
  await expect(versions.getByRole("radio")).toHaveCount(1);

  await record(page, EN, SECOND_TEXT);
  await expect(page.getByRole("status").filter({ hasText: "New version saved: version 2." })).toBeVisible();
  await expect(versions.getByRole("radio")).toHaveCount(2);
  const compare = page.getByRole("region", { name: "Compare versions" });
  let diff = compare.getByRole("list", { name: "Changes from Version 1 to Version 2" });
  await expect(diff.getByRole("listitem")).toHaveText([
    /Platform Engineer/,
    /^−Removed: You run our Kubernetes clusters\.$/,
    /^\+Added: You run our Nomad clusters\.$/,
    /window\.__descriptionXss/,
    /Apply by October\./,
  ]);
  await expectNoA11yViolations(page);
  await snapshot(page, "description-diff");

  // The scanner's find is offline; its (empty) history stays readable.
  await page.getByRole("button", { name: /Source shown$/ }).click();
  await page.getByRole("option", { name: "Found by a scanner (board.history.example)" }).click();
  await expect(page.getByText("offline since Sep 20, 2026")).toBeVisible();
  await page.getByRole("button", { name: /Source shown$/ }).click();
  await page.getByRole("option", { name: "Added from a link (jobs.history.example)" }).click();

  // Applying freezes the latest version.
  await page.getByRole("tab", { name: "Overview" }).click();
  await page.getByRole("button", { name: /Change status$/ }).click();
  await page
    .getByRole("listbox", { name: "Change status" })
    .getByRole("option", { name: "Applied", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Change status" }).click();
  await expect(dialog).toBeHidden();
  await page.getByRole("tab", { name: "Description" }).click();
  await expect(versions.getByRole("radio", { name: /^Version 2.*Frozen when you applied/ })).toBeVisible();
  await expect(versions.getByText("Frozen when you applied")).toHaveCount(1);

  await page.getByRole("button", { name: "Record the current text" }).click();
  await record(page, EN, THIRD_TEXT);
  await expect(page.getByRole("status").filter({ hasText: "New version saved: version 3." })).toBeVisible();
  diff = compare.getByRole("list", { name: "Changes from Version 2 to Version 3" });
  await expect(diff.getByRole("listitem").filter({ hasText: "Added: Remote within Germany." })).toBeVisible();
  await expect(versions.getByRole("radio", { name: /^Version 3/ })).not.toContainText("Frozen");
  await expectNoA11yViolations(page);
  await snapshot(page, "description-frozen");
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("record two texts in German and compare them", async ({ page }) => {
    await page.goto(`/applications/${seededApplication("de")}?tab=description`);
    await expect(page.getByRole("tab", { name: "Beschreibung" })).toHaveAttribute("aria-selected", "true");
    const labels = { field: "Aktueller Text der Anzeige", action: "Text erfassen" };
    // Unique per run, so a repeated run against the same stack still records new versions.
    const stamp = Date.now().toString(36);
    await page.getByRole("button", { name: "Aktuellen Text erfassen" }).click();
    await record(page, labels, `Plattform-Entwicklerin ${stamp}\nVollzeit`);
    await expect(page.getByRole("status").filter({ hasText: /^Neue Version gespeichert/ })).toBeVisible();
    await record(page, labels, `Plattform-Entwicklerin ${stamp}\nTeilzeit`);
    await expect(page.getByRole("status").filter({ hasText: /^Neue Version gespeichert/ })).toBeVisible();

    const diff = page
      .getByRole("region", { name: "Versionen vergleichen" })
      .getByRole("list", { name: /^Änderungen von/ });
    await expect(diff.getByRole("listitem").filter({ hasText: "Entfernt: Vollzeit" })).toBeVisible();
    await expect(diff.getByRole("listitem").filter({ hasText: "Hinzugefügt: Teilzeit" })).toBeVisible();
    await expect(page.getByText(/^Hinzugefügte Zeilen: 1 · entfernte Zeilen: 1$/)).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "description-diff-de");
  });
});
