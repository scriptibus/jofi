// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { expectNoA11yViolations, onStack, snapshot } from "./helpers.ts";

// Settings > Backup as it looks in every browser project (light, dark, phone). Nothing here enters a
// password, uploads or restores: those share the login backoff and the backup lock, and a restore ends
// every session, so they run alone in the `backup` project (tests/backup/backup.spec.ts).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");

test("settings: the backup section warns before export and restore; the export dialog asks for the password", async ({
  page,
}) => {
  await page.goto("/settings");
  const section = page.getByRole("region", { name: "Backup", exact: true });
  await expect(section.getByRole("heading", { level: 2, name: "Backup" })).toBeVisible();
  await expect(section.getByRole("note").filter({ hasText: "A backup grants full access" })).toBeVisible();
  await expect(
    section.getByRole("note").filter({ hasText: "Only restore backups you made yourself" }),
  ).toBeVisible();
  await expect(section.getByRole("button", { name: "Choose backup file…" })).toBeVisible();
  await section.getByRole("button", { name: "Download backup…" }).scrollIntoViewIfNeeded();
  await expectNoA11yViolations(page);
  await snapshot(page, "settings-backup");

  await section.getByRole("button", { name: "Download backup…" }).click();
  const dialog = page.getByRole("dialog", { name: "Download a backup" });
  await expect(dialog.getByLabel("Current password")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "backup-export-dialog");

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("settings: the backup section in German", async ({ page }) => {
    await page.goto("/settings");
    const section = page.getByRole("region", { name: "Datensicherung", exact: true });
    await expect(section.getByText("Eine Sicherung gewährt vollen Zugriff")).toBeVisible();
    await expect(section.getByText("Zurückspielen ersetzt alle Daten")).toBeVisible();
    await section.getByRole("button", { name: "Sicherung herunterladen …" }).click();
    const dialog = page.getByRole("dialog", { name: "Sicherung herunterladen" });
    await expect(dialog.getByLabel("Aktuelles Passwort")).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "backup-export-dialog-de");
    await dialog.getByRole("button", { name: "Abbrechen" }).click();
    await expect(dialog).toBeHidden();
  });
});
