// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { defineConfig, devices, type PlaywrightTestConfig, type Project } from "@playwright/test";
import { E2E_STORAGE_STATE } from "./tests/stack/seed/api.ts";

const previewPort = 4173;
const isCI = Boolean(process.env.CI);
// Set by scripts/e2e-stack.sh (`pnpm e2e`): tests run against the full compose stack, after the `seed`
// project has logged in. Without it (`pnpm e2e:preview`) they run against `vite preview`, no backend.
const stackUrl = process.env.JOFI_E2E_BASE_URL;

const browsers: Project[] = [
  {
    name: "desktop-light",
    use: { ...devices["Desktop Chrome"], colorScheme: "light" },
  },
  {
    name: "desktop-dark",
    use: { ...devices["Desktop Chrome"], colorScheme: "dark" },
  },
  {
    name: "phone",
    use: { ...devices["Pixel 7"], colorScheme: "light", reducedMotion: "reduce" },
  },
];

const target: PlaywrightTestConfig = stackUrl
  ? {
      use: { baseURL: stackUrl },
      projects: [
        // A fresh instance's first visit, through the UI (first run with the setup token).
        {
          name: "first-run",
          testDir: "./tests/stack",
          testMatch: /first-run\.setup\.ts/,
          use: { ...devices["Desktop Chrome"], locale: "de-DE" },
        },
        { name: "seed", testDir: "./tests/stack", testMatch: /seed\.setup\.ts/, dependencies: ["first-run"] },
        ...browsers.map((project) => ({
          ...project,
          dependencies: ["seed"],
          use: { ...project.use, storageState: E2E_STORAGE_STATE },
        })),
        // Adding, assigning, budgeting and deleting change the one AI setup every other test sees, so they
        // run after the browser projects, alone, and put the seeded setup back (tests/ai/ai-setup.spec.ts).
        {
          name: "ai-setup",
          testDir: "./tests/ai",
          testMatch: /ai-setup\.spec\.ts/,
          fullyParallel: false,
          dependencies: browsers.map((project) => project.name ?? ""),
          use: { ...devices["Desktop Chrome"], colorScheme: "light", storageState: E2E_STORAGE_STATE },
        },
        // Files of one project would run in parallel workers, so the costs spec gets a project of its own,
        // after `ai-setup`: it reads the setup and spends on it, and must never meet the setup's changes.
        {
          name: "ai-costs",
          testDir: "./tests/ai",
          testMatch: /ai-costs\.spec\.ts/,
          fullyParallel: false,
          dependencies: ["ai-setup"],
          use: { ...devices["Desktop Chrome"], colorScheme: "light", storageState: E2E_STORAGE_STATE },
        },
        // Model prices change what the seeded model's calls cost, so they run after the costs spec, alone,
        // and remove what they set (tests/ai/ai-prices.spec.ts).
        {
          name: "ai-prices",
          testDir: "./tests/ai",
          testMatch: /ai-prices\.spec\.ts/,
          fullyParallel: false,
          dependencies: ["ai-costs"],
          use: { ...devices["Desktop Chrome"], colorScheme: "light", storageState: E2E_STORAGE_STATE },
        },
        // Wrong passwords and password changes: they share the one-client login backoff with every
        // other test and end the seeded session, so they run last, alone (tests/auth/auth.spec.ts).
        {
          name: "auth",
          testDir: "./tests/auth",
          fullyParallel: false,
          dependencies: ["ai-prices"],
          use: { ...devices["Desktop Chrome"], colorScheme: "light" },
        },
        // Backup export and restore: wrong passwords, the one backup lock, and a restore that ends every
        // session, so they run after everything else, alone (tests/backup/backup.spec.ts).
        {
          name: "backup",
          testDir: "./tests/backup",
          fullyParallel: false,
          dependencies: ["auth"],
          use: { ...devices["Desktop Chrome"], colorScheme: "light" },
        },
      ],
    }
  : {
      use: { baseURL: `http://localhost:${previewPort}` },
      projects: browsers,
      // The production build, served by `vite preview`.
      webServer: {
        command: `pnpm build && pnpm preview --port ${previewPort} --strictPort`,
        url: `http://localhost:${previewPort}`,
        reuseExistingServer: !isCI,
        timeout: 120_000,
      },
    };

export default defineConfig({
  ...target,
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: isCI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    ...target.use,
    locale: "en-US",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
