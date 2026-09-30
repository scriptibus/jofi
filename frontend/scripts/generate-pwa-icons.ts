// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Renders the PWA icons in public/icons from public/donkey.svg and the light tokens, with the
// Playwright Chromium the e2e tests use (no image toolchain needed). The PNGs are committed; run
// this again after changing the donkey or the palette:
//
//   node scripts/generate-pwa-icons.ts
//
// "any" icons fill 84 % of the square, the maskable one 60 % (inside the 80 % safe zone).

import { mkdir, readFile } from "node:fs/promises";
import { chromium } from "@playwright/test";
import { themeColours } from "../src/pwa/manifest.ts";

const root = new URL("../", import.meta.url);
const colours = themeColours(await readFile(new URL("src/styles/tokens.css", root), "utf8")).light;
const donkey = (await readFile(new URL("public/donkey.svg", root), "utf8"))
  .replace(/<style>[\s\S]*<\/style>/, "")
  .replace(/<title>[\s\S]*<\/title>/, "")
  .replaceAll('class="f"', `fill="${colours.foreground}"`)
  .replaceAll('class="a"', `fill="${colours.accent}"`)
  .replaceAll('class="b"', `fill="${colours.background}"`);

const icons = [
  { file: "icon-192.png", size: 192, scale: 0.84 },
  { file: "icon-512.png", size: 512, scale: 0.84 },
  { file: "icon-maskable-512.png", size: 512, scale: 0.6 },
  { file: "apple-touch-icon-180.png", size: 180, scale: 0.72 },
];

const outDir = new URL("public/icons/", root);
await mkdir(outDir, { recursive: true });
const browser = await chromium.launch();
try {
  for (const { file, size, scale } of icons) {
    const page = await browser.newPage({ viewport: { width: size, height: size } });
    const inner = Math.round(size * scale);
    await page.setContent(
      `<body style="margin:0;display:grid;place-items:center;width:${size}px;height:${size}px;background:${colours.background}">` +
        donkey.replace("<svg ", `<svg width="${inner}" height="${inner}" `) +
        "</body>",
    );
    await page.screenshot({ path: new URL(file, outDir).pathname, omitBackground: false });
    await page.close();
    console.log(`public/icons/${file}`);
  }
} finally {
  await browser.close();
}
