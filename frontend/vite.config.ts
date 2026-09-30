// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { paraglideVitePlugin } from "@inlang/paraglide-js";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

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
  },
});
