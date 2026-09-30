// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { m } from "../../paraglide/messages.js";
import { DonkeyLogo } from "../../ui";
import { LanguageSwitch, usePageTitle } from "../preferences";

export interface AuthLayoutProps {
  title: string;
  intro: string;
  children: ReactNode;
}

/** The frame of the login and first-run screens: brand, one card, language switch. */
export function AuthLayout({ title, intro, children }: AuthLayoutProps) {
  usePageTitle(title);
  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-md flex-col gap-8 px-4 py-10 sm:py-16">
      <header className="flex items-center gap-3">
        <DonkeyLogo className="size-10" />
        <div className="flex flex-col">
          <span className="font-display font-heading text-h2 tracking-display">{m.app_name()}</span>
          <span className="font-data text-eyebrow text-muted uppercase">{m.app_tagline()}</span>
        </div>
      </header>
      <main
        id="main"
        className="flex flex-col gap-6 rounded border border-line bg-surface p-6 shadow-card sm:p-8"
      >
        <div className="flex flex-col gap-2">
          <h1 className="text-h2">{title}</h1>
          <p className="text-muted">{intro}</p>
        </div>
        {children}
      </main>
      <footer>
        <LanguageSwitch />
      </footer>
    </div>
  );
}
