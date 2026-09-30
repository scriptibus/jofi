// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Outlet, rootRouteId, useLocation, useRouteContext } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { m } from "../../paraglide/messages.js";
import { DonkeyLogo, NavItem, SettingsIcon } from "../../ui";
import { LogoutButton } from "./LogoutButton";
import { NAVIGATION } from "./navigation";
import { ProblemNotices } from "./ProblemNotices";

function Brand() {
  return (
    <span className="flex items-center gap-2.5">
      <DonkeyLogo className="size-8" />
      <span className="font-display font-heading text-h3 tracking-display">{m.app_name()}</span>
    </span>
  );
}

/**
 * The logged-in frame. Phone: a top bar (brand, settings) and a bottom tab bar. From `md` on: a
 * sidebar with every item and logout. One `<nav>` restyled per breakpoint, so the landmark is unique.
 */
export function AppShell() {
  const { notices } = useRouteContext({ from: rootRouteId });
  const pathname = useLocation({ select: (location) => location.pathname });
  const main = useRef<HTMLElement>(null);
  const shownPath = useRef(pathname);

  // After client-side navigation, move focus to the new page, like a page load would (WCAG 2.4.3),
  // and drop the old page's notices.
  useEffect(() => {
    if (shownPath.current === pathname) return;
    shownPath.current = pathname;
    notices.clear();
    main.current?.focus();
  }, [pathname, notices]);

  return (
    <div className="min-h-dvh md:flex">
      <a
        href="#main"
        className="sr-only rounded bg-surface px-3 py-2 shadow-card focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-30"
      >
        {m.skip_to_content()}
      </a>

      <header className="sticky top-0 z-20 flex items-center justify-between border-line border-b bg-bg px-4 py-2 md:hidden">
        <Brand />
        <NavItem to="/settings" className="md:hidden">
          <SettingsIcon className="size-5" aria-hidden="true" />
          <span>{m.nav_settings()}</span>
        </NavItem>
      </header>

      <div className="md:sticky md:top-0 md:flex md:h-dvh md:w-60 md:shrink-0 md:flex-col md:gap-8 md:border-line md:border-r md:px-3 md:py-6">
        <div className="hidden px-3 md:block">
          <Brand />
        </div>
        <nav
          aria-label={m.nav_label()}
          className="fixed inset-x-0 bottom-0 z-20 border-line border-t bg-surface md:static md:border-0 md:bg-transparent"
        >
          <ul className="grid grid-cols-5 md:flex md:flex-col md:gap-1">
            {NAVIGATION.map(({ to, label, icon: ItemIcon, phone }) => (
              <li key={to} className={phone ? undefined : "hidden md:block"}>
                <NavItem to={to} activeOptions={{ exact: to === "/" }} variant="tab">
                  <ItemIcon className="size-5 shrink-0" aria-hidden="true" />
                  <span className="max-w-full truncate">{label()}</span>
                </NavItem>
              </li>
            ))}
          </ul>
        </nav>
        <div className="mt-auto hidden md:block">
          <LogoutButton />
        </div>
      </div>

      <main
        id="main"
        ref={main}
        tabIndex={-1}
        className="mx-auto flex w-full max-w-page flex-1 flex-col gap-6 px-4 pt-6 pb-28 outline-none sm:px-8 md:pt-10 md:pb-12"
      >
        <ProblemNotices store={notices} />
        <Outlet />
      </main>
    </div>
  );
}
