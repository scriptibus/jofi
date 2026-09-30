// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { defineConfig, devices } from "@playwright/test";

const port = 4173;
const isCI = Boolean(process.env.CI);

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  reporter: isCI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://localhost:${port}`,
    locale: "en-US",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
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
  ],
  // Tests run against the production build, served by `vite preview`.
  webServer: {
    command: `pnpm build && pnpm preview --port ${port} --strictPort`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !isCI,
    timeout: 120_000,
  },
});
