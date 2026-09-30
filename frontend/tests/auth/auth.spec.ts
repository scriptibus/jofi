// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Password flows against the real backend, in the `auth` project (playwright.config.ts). All browser
// traffic reaches the app from the one `edge` address, so every wrong password here counts against
// the backoff the other tests share (ADR-0035, ADR-0036). This project therefore runs alone, after
// every other project, one test at a time; each test ends with a successful password check, which
// resets the backoff. A password change also ends every other session, the seeded one included.

import { expect, type Page, test } from "@playwright/test";
import { expectNoA11yViolations, snapshot } from "../e2e/helpers.ts";
import { E2E_PASSWORD } from "../stack/seed/api.ts";

test.describe.configure({ mode: "serial" });

async function logIn(page: Page, password: string) {
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Log in" }).click();
}

test("login with a wrong then the right password, expired session, logout", async ({ page, context }) => {
  await page.goto("/tasks");
  await expect(page.getByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();

  await logIn(page, "definitely not the password");
  await expect(page.getByText("Wrong password. Please try again.")).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "login-wrong-password");

  await logIn(page, E2E_PASSWORD);
  await expect(page.getByRole("heading", { level: 1, name: "Tasks" })).toBeVisible();

  // The session ends behind the app's back: the next API call answers 401 and the app returns to login.
  await context.clearCookies({ name: "SESSION" });
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Settings" }).click();
  await expect(page.getByText("Your session has ended. Please log in again.")).toBeVisible();
  await expect(page).toHaveURL(/\/login\?.*reason=expired/);

  await logIn(page, E2E_PASSWORD);
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();

  await page.getByRole("button", { name: "Log out" }).click();
  await expect(page.getByText("You are logged out.")).toBeVisible();
  await snapshot(page, "logged-out");
  const api = await page.request.get("/api/system/info");
  expect(api.status()).toBe(401);
  await page.goto("/settings");
  await expect(page).toHaveURL(/\/login/);
});

test("password change: wrong current password, then change and change back", async ({ page }) => {
  await page.goto("/settings");
  await logIn(page, E2E_PASSWORD);
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();

  const change = async (current: string, next: string) => {
    await page.getByLabel("Current password").fill(current);
    await page.getByLabel("New password").fill(next);
    await page.getByLabel("Repeat the password").fill(next);
    await page.getByRole("button", { name: "Change password" }).click();
  };
  const newPassword = `${E2E_PASSWORD}-changed`;

  await change("not my current password", newPassword);
  await expect(page.getByText("The current password is wrong.")).toBeVisible();
  await expectNoA11yViolations(page);

  await change(E2E_PASSWORD, newPassword);
  await expect(page.getByText("Your password was changed. Other devices are logged out.")).toBeVisible();
  await expect(page.getByLabel("Current password")).toHaveValue("");
  await expectNoA11yViolations(page);
  await snapshot(page, "password-changed");

  // Back to the seeded password, so a reused stack still logs in.
  await change(newPassword, E2E_PASSWORD);
  await expect(page.getByText("Your password was changed. Other devices are logged out.")).toBeVisible();
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("backoff: after repeated wrong passwords the login asks to wait, then works", async ({ page }) => {
    await page.goto("/login");
    const password = page.getByLabel("Passwort");
    const submit = page.getByRole("button", { name: "Anmelden" });
    const throttled = page.getByText(/^Zu viele Fehlversuche\. Bitte warte \d+ Sekunden/);

    // 5 failures are free (ADR-0035); keep guessing until the server answers 429.
    for (let attempt = 1; attempt <= 8; attempt++) {
      await password.fill(`wrong guess number ${attempt}`);
      const response = page.waitForResponse("**/api/auth/login");
      await submit.click();
      if ((await response).status() === 429) break;
    }
    await expect(throttled).toBeVisible();
    await expect(submit).toBeDisabled();
    await expectNoA11yViolations(page);
    await snapshot(page, "login-throttled-de");

    // The button comes back when Retry-After has passed; the right password resets the backoff.
    await expect(page.getByText("Du kannst es jetzt noch einmal versuchen.")).toBeVisible({
      timeout: 60_000,
    });
    await expect(submit).toBeEnabled();
    await password.fill(E2E_PASSWORD);
    await submit.click();
    await expect(
      page.getByRole("heading", { level: 1, name: "Lass den Esel die Eselsarbeit machen." }),
    ).toBeVisible();
  });
});
