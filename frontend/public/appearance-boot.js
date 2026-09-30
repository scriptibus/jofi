// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Runs synchronously in <head> so the stored theme and accent apply before the first paint.
// Keys and allowed values must match src/ui/appearance.ts (a unit test checks this).
(() => {
  const root = document.documentElement;
  try {
    const theme = localStorage.getItem("jofi.theme");
    if (theme === "light" || theme === "dark") root.dataset.theme = theme;
    const accent = localStorage.getItem("jofi.accent");
    if (accent === "cobalt" || accent === "teal" || accent === "plum" || accent === "ink") {
      root.dataset.accent = accent;
    }
  } catch {
    // Storage blocked (private mode, policy): fall back to system theme and default accent.
  }
})();
