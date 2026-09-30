// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { expectNoA11yViolations, onStack, snapshot } from "./helpers.ts";

// Without the seeded session: what a stranger (or a logged-out user) sees. No password is entered
// here, so these tests never touch the login backoff (ADR-0035, ADR-0036).
test.skip(!onStack, "Needs the full stack: run `pnpm e2e`.");
test.use({ storageState: { cookies: [], origins: [] } });

test("auth guard: every app page redirects to login and keeps the target", async ({ page }) => {
  for (const path of ["/", "/applications", "/settings", "/share?url=https%3A%2F%2Fjobs.example%2F1"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
    const redirect = new URL(page.url()).searchParams.get("redirect");
    expect(redirect ?? "/").toBe(path);
  }
  // Nothing of the app shell leaks before login.
  await expect(page.getByRole("navigation")).toHaveCount(0);

  const api = await page.request.get("/api/system/info");
  expect(api.status()).toBe(401);
});

test("login screen: accessible, validates before sending, first run is closed", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
  await expectNoA11yViolations(page);
  await snapshot(page, "login");

  // An empty submit is caught in the browser (no request, so no backoff).
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page.getByText("Enter your password.")).toBeVisible();
  await expectNoA11yViolations(page);

  // The seed completed first run: its screen now sends users to login.
  await page.goto("/first-run");
  await expect(page).toHaveURL(/\/login$/);
});

test.describe("in German", () => {
  test.use({ locale: "de-DE" });

  test("login screen follows the browser language", async ({ page }) => {
    await page.goto("/tasks");
    await expect(page.getByRole("heading", { level: 1, name: "Willkommen zurück" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
    await expect(page.getByRole("button", { name: "Anmelden" })).toBeVisible();
    await expectNoA11yViolations(page);
    await snapshot(page, "login-de");
  });
});
