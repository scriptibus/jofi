// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";

// Proves the full stack is wired: the SPA and the API come from the same Spring Boot app, behind the
// e2e edge, with the seeded user's session (once login exists, #16, every /api call needs it).
test.skip(!process.env.JOFI_E2E_BASE_URL, "Needs the full stack: run `pnpm e2e`.");

test("stack: the app serves the SPA and answers the API with the e2e session", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  const response = await page.request.get("/api/system/info");
  expect(response.status()).toBe(200);
  const info: unknown = await response.json();
  expect(info).toEqual({ name: expect.any(String), version: expect.stringMatching(/\S/) });
});
