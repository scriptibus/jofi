// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useState } from "react";
import { m } from "../paraglide/messages.js";
import { getLocale, type Locale, locales, setLocale } from "../paraglide/runtime.js";
import {
  type Accent,
  AccentSwatch,
  Button,
  DonkeyLogo,
  SegmentedControl,
  type Theme,
  useAccent,
  useTheme,
} from "../ui";

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

export function App() {
  const [theme, setTheme] = useTheme();
  const [accent, setAccent] = useAccent();
  const [working, setWorking] = useState(false);

  return (
    <div className="mx-auto flex min-h-dvh max-w-page flex-col px-5 sm:px-8">
      <a
        href="#main"
        className="sr-only rounded bg-surface px-3 py-2 shadow-card focus:not-sr-only focus:absolute focus:top-3 focus:left-3"
      >
        {m.skip_to_content()}
      </a>

      <header className="flex items-center gap-3 py-6">
        <DonkeyLogo className="size-9" />
        <span className="font-display font-heading text-h2 tracking-display">{m.app_name()}</span>
      </header>

      <main id="main" className="grid flex-1 gap-12 py-8 lg:grid-cols-2 lg:gap-16 lg:py-16">
        <section aria-labelledby="page-heading" className="flex max-w-prose flex-col gap-5">
          <p className="font-data text-eyebrow text-muted uppercase">{m.eyebrow()}</p>
          <h1 id="page-heading" className="text-display">
            {m.heading()}
          </h1>
          <p className="text-lede text-muted">{m.lede()}</p>

          <div className="mt-6 flex flex-col items-start gap-5 rounded border border-line bg-surface p-6 shadow-card transition-spring hover:-translate-y-0.75">
            <h2 className="text-h3">{m.loader_heading()}</h2>
            <div className="flex items-center gap-5">
              {/* Decorative here: the status text next to it carries the meaning. */}
              <DonkeyLogo className="size-16" loading={working} />
              <p aria-live="polite" className="text-muted">
                {working ? m.loader_working() : m.loader_idle()}
              </p>
            </div>
            <Button variant={working ? "secondary" : "primary"} onPress={() => setWorking((w) => !w)}>
              {working ? m.loader_stop() : m.loader_start()}
            </Button>
          </div>
        </section>

        <section
          aria-labelledby="appearance-heading"
          className="flex h-fit flex-col gap-6 rounded border border-line bg-surface p-6 shadow-card"
        >
          <h2 id="appearance-heading" className="text-h2">
            {m.appearance_heading()}
          </h2>
          <SegmentedControl
            label={m.theme_label()}
            value={theme}
            onChange={setTheme}
            options={(Object.keys(themeLabels) as Theme[]).map((value) => ({
              value,
              label: themeLabels[value](),
            }))}
          />
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
          <SegmentedControl
            label={m.language_label()}
            value={getLocale()}
            onChange={(locale) => setLocale(locale)}
            options={locales.map((value) => ({
              value,
              label: languageLabels[value](),
              lang: value,
            }))}
          />
        </section>
      </main>
    </div>
  );
}
