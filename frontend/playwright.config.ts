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
        // Wrong passwords and password changes: they share the one-client login backoff with every
        // other test and end the seeded session, so they run last, alone (tests/auth/auth.spec.ts).
        {
          name: "auth",
          testDir: "./tests/auth",
          fullyParallel: false,
          dependencies: browsers.map((project) => project.name ?? ""),
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
