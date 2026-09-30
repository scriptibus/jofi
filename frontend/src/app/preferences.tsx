// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useEffect } from "react";
import { m } from "../paraglide/messages.js";
import { getLocale, type Locale, locales, setLocale } from "../paraglide/runtime.js";
import { type Accent, AccentSwatch, SegmentedControl, type Theme, useAccent, useTheme } from "../ui";

const themeLabels: Record<Theme, () => string> = {
  system: m.theme_system,
  light: m.theme_light,
  dark: m.theme_dark,
};

const accentLabels: Record<Accent, () => string> = {
  saffron: m.accent_saffron,
  cobalt: m.accent_cobalt,
  teal: m.accent_teal,
  plum: m.accent_plum,
  ink: m.accent_ink,
};

const languageLabels: Record<Locale, () => string> = {
  de: m.language_de,
  en: m.language_en,
};

/** DE/EN switch. Paraglide stores the choice (localStorage) and reloads the page. */
export function LanguageSwitch() {
  return (
    <SegmentedControl
      label={m.language_label()}
      value={getLocale()}
      onChange={(locale) => setLocale(locale)}
      options={locales.map((value) => ({ value, label: languageLabels[value](), lang: value }))}
    />
  );
}

export function ThemeSwitch() {
  const [theme, setTheme] = useTheme();
  return (
    <SegmentedControl
      label={m.theme_label()}
      value={theme}
      onChange={setTheme}
      options={(Object.keys(themeLabels) as Theme[]).map((value) => ({
        value,
        label: themeLabels[value](),
      }))}
    />
  );
}

export function AccentSwitch() {
  const [accent, setAccent] = useAccent();
  return (
    <SegmentedControl
      label={m.accent_label()}
      value={accent}
      onChange={setAccent}
      options={(Object.keys(accentLabels) as Accent[]).map((value) => ({
        value,
        label: accentLabels[value](),
        icon: <AccentSwatch accent={value} />,
      }))}
    />
  );
}

/** Sets the document title (WCAG 2.4.2) to "<page> · Jofi". */
export function usePageTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · ${m.app_name()}`;
  }, [title]);
}
