// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { expect, test } from "@playwright/test";
import { onStack } from "./helpers.ts";

interface Manifest {
  name: string;
  start_url: string;
  display: string;
  theme_color: string;
  icons: { src: string; sizes: string; purpose: string }[];
  share_target: { action: string; method: string; params: Record<string, string> };
}

test("PWA: the manifest is served with icons and the share target", async ({ page, request }) => {
  await page.goto("/login");
  const href = await page.locator('link[rel="manifest"]').getAttribute("href");
  expect(href).toBe("/manifest.webmanifest");

  const response = await request.get("/manifest.webmanifest");
  expect(response.status()).toBe(200);
  const manifest = (await response.json()) as Manifest;
  expect(manifest).toMatchObject({ name: "Jofi", start_url: "/", display: "standalone" });
  expect(manifest.theme_color).toMatch(/^#[0-9a-f]{6}$/i);
  expect(manifest.share_target).toMatchObject({
    action: "/share",
    method: "GET",
    params: { title: "title", text: "text", url: "url" },
  });
  for (const icon of manifest.icons) {
    const image = await request.get(icon.src);
    expect(image.status(), icon.src).toBe(200);
    expect(image.headers()["content-type"]).toContain("image/png");
  }
});

test("PWA: installable, and the service worker never caches API responses", async ({ page, browserName }) => {
  test.skip(browserName !== "chromium", "Installability is checked through the Chrome DevTools Protocol.");
  test.skip(!onStack, "Needs the full stack (API responses to not cache): run `pnpm e2e`.");

  await page.goto("/settings");
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
  // The settings page has fetched the session and the system info (personal-data-free here, but
  // the rule is about any API response).
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
  await expect(page.getByText(/^Jofi \S+$/)).toBeVisible();

  const controlled = await page.evaluate(() => navigator.serviceWorker.controller !== null);
  expect(controlled).toBe(true);

  const cached = await page.evaluate(async () => {
    const urls: string[] = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) urls.push(new URL(request.url).pathname);
    }
    return urls;
  });
  expect(cached).toContain("/index.html");
  expect(cached.filter((path) => path.startsWith("/api/"))).toEqual([]);

  const cdp = await page.context().newCDPSession(page);
  const { installabilityErrors } = (await cdp.send("Page.getInstallabilityErrors")) as {
    installabilityErrors: { errorId: string }[];
  };
  expect(installabilityErrors).toEqual([]);
});
