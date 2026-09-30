// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useCallback, useState } from "react";

/**
 * Theme and accent preferences.
 *
 * Both are applied as attributes on <html> (see src/styles/tokens.css):
 * - data-theme="light" | "dark"; absent = follow the OS ("system")
 * - data-accent="cobalt" | "teal" | "plum" | "ink"; absent = saffron (default)
 *
 * public/appearance-boot.js repeats the storage keys and values so the
 * preference applies before the first paint; a unit test keeps them in sync.
 */

export const THEMES = ["system", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

export const ACCENTS = ["saffron", "cobalt", "teal", "plum", "ink"] as const;
export type Accent = (typeof ACCENTS)[number];

export const DEFAULT_THEME: Theme = "system";
export const DEFAULT_ACCENT: Accent = "saffron";

export const THEME_STORAGE_KEY = "jofi.theme";
export const ACCENT_STORAGE_KEY = "jofi.accent";

const isTheme = (value: unknown): value is Theme =>
  typeof value === "string" && (THEMES as readonly string[]).includes(value);

const isAccent = (value: unknown): value is Accent =>
  typeof value === "string" && (ACCENTS as readonly string[]).includes(value);

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // Storage unavailable (private mode, blocked site data): the choice lasts for this page only.
  }
}

export function readTheme(): Theme {
  const stored = readStorage(THEME_STORAGE_KEY);
  return isTheme(stored) ? stored : DEFAULT_THEME;
}

export function readAccent(): Accent {
  const stored = readStorage(ACCENT_STORAGE_KEY);
  return isAccent(stored) ? stored : DEFAULT_ACCENT;
}

export function applyTheme(theme: Theme, root: HTMLElement = document.documentElement): void {
  if (theme === "system") delete root.dataset.theme;
  else root.dataset.theme = theme;
}

export function applyAccent(accent: Accent, root: HTMLElement = document.documentElement): void {
  if (accent === DEFAULT_ACCENT) delete root.dataset.accent;
  else root.dataset.accent = accent;
}

export function setTheme(theme: Theme): void {
  applyTheme(theme);
  writeStorage(THEME_STORAGE_KEY, theme === DEFAULT_THEME ? null : theme);
}

export function setAccent(accent: Accent): void {
  applyAccent(accent);
  writeStorage(ACCENT_STORAGE_KEY, accent === DEFAULT_ACCENT ? null : accent);
}

/** Applies the stored preferences; call once at start-up. */
export function initAppearance(): void {
  applyTheme(readTheme());
  applyAccent(readAccent());
}

/** React state for the theme preference, persisted and applied to <html>. */
export function useTheme(): [Theme, (theme: Theme) => void] {
  const [theme, setState] = useState<Theme>(readTheme);
  const update = useCallback((next: Theme) => {
    setTheme(next);
    setState(next);
  }, []);
  return [theme, update];
}

/** React state for the accent preset, persisted and applied to <html>. */
export function useAccent(): [Accent, (accent: Accent) => void] {
  const [accent, setState] = useState<Accent>(readAccent);
  const update = useCallback((next: Accent) => {
    setAccent(next);
    setState(next);
  }, []);
  return [accent, update];
}

export { isAccent, isTheme };
