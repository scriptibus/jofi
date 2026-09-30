// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Playwright setup project `first-run` of the full-stack run: the very first visit of a fresh
// instance, through the UI and in German. It runs before `seed`, which then only logs in. On a
// reused stack (E2E_REUSE_STACK=1) first run is long done and this is skipped.

import { expect, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";
import { E2E_PASSWORD } from "./seed/api.ts";

test("first run: a wrong setup token is refused, the right one sets Jofi up", async ({ page, request }) => {
  const session = (await (await request.get("/api/auth/session")).json()) as { setUp: boolean };
  test.skip(session.setUp, "This stack is already set up (reused stack).");
  const setupToken = process.env.JOFI_E2E_SETUP_TOKEN ?? "";
  expect(setupToken, "scripts/e2e-stack.sh passes the setup token").not.toBe("");

  await page.goto("/applications");
  await expect(page).toHaveURL(/\/first-run$/);
  await expect(page.getByRole("heading", { level: 1, name: "Jofi einrichten" })).toBeVisible();
  await expect(page.getByText("docker compose exec app cat /data/secrets/setup-token")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "first-run");

  // Password rules are checked in the browser before anything is sent.
  await page.getByLabel("Passwort", { exact: true }).fill("zu kurz");
  await page.getByRole("button", { name: "Passwort festlegen" }).click();
  await expect(page.getByText("Verwende mindestens 15 Zeichen.")).toBeVisible();

  await page.getByLabel("Passwort", { exact: true }).fill(E2E_PASSWORD);
  await page.getByLabel("Passwort wiederholen").fill(E2E_PASSWORD);
  await page.getByLabel("Einrichtungs-Token").fill("not-the-setup-token");
  await page.getByRole("button", { name: "Passwort festlegen" }).click();
  await expect(
    page.getByText("Das Einrichtungs-Token ist falsch. Kopiere es noch einmal vom Server."),
  ).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "first-run-wrong-token");

  await page.getByLabel("Einrichtungs-Token").fill(setupToken);
  await page.getByRole("button", { name: "Passwort festlegen" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: "Lass den Esel die Eselsarbeit machen." }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
});
