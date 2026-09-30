// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Playwright setup project `seed` of the full-stack run (`pnpm e2e`, playwright.config.ts): runs the API
// seed steps once and saves the logged-in browser state every test project starts from.

import { mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { test as setup } from "@playwright/test";
import { E2E_STORAGE_STATE, logInAsE2eUser } from "./seed/api.ts";

setup("seed: the e2e user is logged in", async ({ request }) => {
  const auth = await logInAsE2eUser(request);
  setup.info().annotations.push({
    type: "auth",
    description: auth === "logged-in" ? "logged in as the e2e user" : "this build has no login yet",
  });
  await mkdir(dirname(E2E_STORAGE_STATE), { recursive: true });
  await request.storageState({ path: E2E_STORAGE_STATE });
});
