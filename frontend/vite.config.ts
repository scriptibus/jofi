// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { readFileSync } from "node:fs";
import { paraglideVitePlugin } from "@inlang/paraglide-js";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { defineConfig } from "vitest/config";
import { themeColourMeta, themeColours, webAppManifest } from "./src/pwa/manifest.ts";

const colours = themeColours(readFileSync(new URL("./src/styles/tokens.css", import.meta.url), "utf8"));

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    paraglideVitePlugin({
      project: "./project.inlang",
      outdir: "./src/paraglide",
      // .d.ts output so `tsc` sees message types without allowJs. With TypeScript 7
      // Paraglide runs the `tsc` CLI in a child process for this.
      emitTsDeclarations: true,
      // Manual choice (localStorage) wins, then the browser language, then English.
      // Keep in sync with the `i18n` script in package.json (used by `pnpm typecheck`).
      strategy: ["localStorage", "preferredLanguage", "baseLocale"],
    }),
    // Installable PWA (ADR-0018). The service worker precaches the app shell only (HTML, JS, CSS,
    // fonts, icons) and has no runtime caching: no API response, so no personal data, is ever
    // stored by it. Navigations fall back to the cached index.html, except the server's own paths.
    VitePWA({
      registerType: "autoUpdate",
      injectRegister: "script-defer",
      manifest: webAppManifest(colours),
      // The glob below already precaches public/ (icons included); listing them twice duplicates entries.
      includeManifestIcons: false,
      workbox: {
        globPatterns: ["**/*.{html,js,css,woff2,svg,png}"],
        navigateFallback: "index.html",
        navigateFallbackDenylist: [/^\/api(\/|$)/, /^\/actuator(\/|$)/],
        runtimeCaching: [],
        cleanupOutdatedCaches: true,
      },
    }),
    {
      name: "jofi-theme-colour",
      transformIndexHtml: (html) => html.replace("<!-- theme-color -->", themeColourMeta(colours)),
    },
  ],
  server: {
    port: 5173,
    strictPort: true,
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
    // Vitest blanks CSS by default; the manifest test reads the tokens as raw text.
    css: { include: [/tokens\.css/] },
  },
});
