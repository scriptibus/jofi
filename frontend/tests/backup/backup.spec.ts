// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Backup export and restore against the real backend, in the `backup` project (playwright.config.ts).
// A restore replaces all data and ends every session, uploads and restores hold the one backup lock,
// and export and restore check the password against the backoff every test shares (ADR-0035, ADR-0036,
// ADR-0042). So this project runs alone, after every other one, one test at a time; each wrong password
// is followed by the right one, which resets the backoff.

import { readFile, writeFile } from "node:fs/promises";
import { devices, expect, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";
import { E2E_PASSWORD } from "../stack/seed/api.ts";

test.describe.configure({ mode: "serial" });

async function logIn(page: Page, label = "Password", submit = "Log in") {
  await page.getByLabel(label).fill(E2E_PASSWORD);
  await page.getByRole("button", { name: submit }).click();
}

/** The API as the logged-in browser, with the CSRF header the backend wants (ADR-0035). */
async function csrfHeaders(page: Page) {
  const cookie = (await page.context().cookies()).find((entry) => entry.name === "XSRF-TOKEN");
  if (cookie === undefined) throw new Error("no XSRF-TOKEN cookie");
  return { "X-XSRF-TOKEN": decodeURIComponent(cookie.value) };
}

/** An AI provider with a stored (encrypted) key: data, a secret and the keyset all travel in a backup. */
async function createProvider(page: Page, displayName: string) {
  const response = await page.request.post("/api/setup/providers", {
    data: { kind: "ANTHROPIC", displayName, apiKey: "e2e-placeholder-key" },
    headers: await csrfHeaders(page),
  });
  expect(response.status()).toBe(201);
}

async function providers(page: Page, prefix: string): Promise<{ displayName: string; apiKeySet: boolean }[]> {
  const response = await page.request.get("/api/setup/providers");
  expect(response.status()).toBe(200);
  const body = (await response.json()) as { displayName: string; apiKeySet: boolean }[];
  return body
    .filter((provider) => provider.displayName.startsWith(prefix))
    .map(({ displayName, apiKeySet }) => ({ displayName, apiKeySet }));
}

async function chooseFile(page: Page, button: string, path: string) {
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: button }).click();
  await (await chooser).setFiles(path);
}

test.describe("in German, dark, at phone width", () => {
  const { defaultBrowserType: _browser, ...pixel } = devices["Pixel 7"];
  test.use({ ...pixel, locale: "de-DE", colorScheme: "dark", reducedMotion: "reduce" });

  test("an upload that is no backup is refused with the reason", async ({ page }, testInfo) => {
    await page.goto("/settings");
    await logIn(page, "Passwort", "Anmelden");
    await expect(page.getByRole("heading", { level: 1, name: "Einstellungen" })).toBeVisible();
    await expect(
      page.getByText("Spiel nur Sicherungen zurück, die du selbst erstellt hast", { exact: false }),
    ).toBeVisible();

    const bogus = testInfo.outputPath("kaputt.zip");
    await writeFile(bogus, "das ist keine Sicherung");
    await chooseFile(page, "Sicherungsdatei wählen …", bogus);
    await expect(
      page.getByText("Diese Datei ist keine Jofi-Sicherung: Sie ist keine lesbare ZIP-Datei."),
    ).toBeVisible();
    await expect(page.getByRole("region", { name: "Hochgeladene Sicherung" })).toHaveCount(0);
    await expectNoA11yViolations(page);
    await snapshot(page, "backup-refused-de");
  });
});

test("export, change, upload, confirm the restore: logged out, then the data is the backup's", async ({
  page,
}, testInfo) => {
  const run = `backup-e2e-${Date.now().toString(36)}`;
  const kept = `${run} kept`;
  const dropped = `${run} dropped`;
  await page.goto("/settings");
  await logIn(page);
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
  await createProvider(page, kept);

  // Export: a wrong password first, then the right one.
  await page.getByRole("button", { name: "Download backup…" }).click();
  const exportDialog = page.getByRole("dialog", { name: "Download a backup" });
  const exportPassword = exportDialog.getByLabel("Current password");
  await exportPassword.fill("not my current password");
  await exportDialog.getByRole("button", { name: "Download" }).click();
  await expect(exportDialog.getByText("The current password is wrong.")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "backup-export-wrong-password");

  await exportPassword.fill(E2E_PASSWORD);
  const downloading = page.waitForEvent("download");
  await exportDialog.getByRole("button", { name: "Download" }).click();
  const download = await downloading;
  expect(download.suggestedFilename()).toMatch(/^jofi-backup-\d{8}-\d{6}\.zip$/);
  const zip = testInfo.outputPath(download.suggestedFilename());
  await download.saveAs(zip);
  const bytes = await readFile(zip);
  expect(bytes.length).toBeGreaterThan(1000);
  expect(bytes.subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 3, 4]));
  await expect(
    page.getByText(`Backup downloaded as ${download.suggestedFilename()}.`, { exact: false }),
  ).toBeVisible();
  await expect(exportDialog).toBeHidden();
  await snapshot(page, "backup-exported");

  // A change after the backup, which the restore must undo.
  await createProvider(page, dropped);

  // Upload the same file: the manifest summary comes first.
  await chooseFile(page, "Choose backup file…", zip);
  const summary = page.getByRole("region", { name: "Uploaded backup" });
  await expect(summary).toBeVisible();
  await expect(summary.getByText("Not needed")).toBeVisible();
  await expect(summary.getByText("Included")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "backup-staged");

  // The restore needs the current password too; a wrong one is refused before any question.
  const restorePassword = page.getByLabel("Current password, to confirm the restore");
  const restore = page.getByRole("button", { name: "Restore this backup…" });
  await restorePassword.fill("still not my current password");
  await restore.click();
  await expect(page.getByText("The current password is wrong.")).toBeVisible();
  await restorePassword.fill(E2E_PASSWORD);
  await restore.click();

  // The dialog shows the server's effect; Cancel runs nothing.
  const confirm = page.getByRole("alertdialog", { name: "Replace all data?" });
  await expect(confirm).toContainText(/database rows and \d+ files, with the key for the stored API keys/);
  await expectNoA11yViolations(page);
  await snapshot(page, "backup-restore-confirm");
  await confirm.getByRole("button", { name: "Cancel" }).click();
  await expect(confirm).toBeHidden();
  expect(await providers(page, run)).toContainEqual({ displayName: dropped, apiKeySet: true });

  await restore.click();
  await confirm.getByRole("button", { name: "Replace all data" }).click();
  await expect(page.getByText(/^The backup was restored and every session has ended/)).toBeVisible({
    timeout: 60_000,
  });
  await expect(page).toHaveURL(/\/login\?.*reason=restored/);
  expect((await page.request.get("/api/system/info")).status()).toBe(401);
  await expectNoA11yViolations(page);
  await snapshot(page, "backup-restored-login");

  // The backup's password (the same here) logs in again; the data is as it was at the export.
  await logIn(page);
  await expect(
    page.getByRole("heading", { level: 1, name: "Let the donkey do the donkey work." }),
  ).toBeVisible();
  // The provider from before the export is back with its stored key; the one created after it is gone.
  expect(await providers(page, run)).toEqual([{ displayName: kept, apiKeySet: true }]);
});
