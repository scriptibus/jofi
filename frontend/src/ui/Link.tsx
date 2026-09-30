// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createLink } from "@tanstack/react-router";
import { Link as AriaLink, type LinkProps as AriaLinkProps, composeRenderProps } from "react-aria-components";

const focusRing =
  "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent";

function joinClasses(base: string, className: AriaLinkProps["className"]) {
  return composeRenderProps(className, (custom) => [base, custom].filter(Boolean).join(" "));
}

/** An inline text link: underlined, so it never relies on colour alone. */
function TextLinkBase({ className, ...props }: AriaLinkProps) {
  return (
    <AriaLink
      {...props}
      className={joinClasses(
        `rounded font-semibold text-fg underline decoration-accent decoration-2 underline-offset-4 data-hovered:decoration-fg ${focusRing}`,
        className,
      )}
    />
  );
}

export type NavItemVariant = "row" | "tab";

const navLayouts: Record<NavItemVariant, string> = {
  row: "flex-row gap-2 px-3 py-2 text-body",
  // Phone: icon above a small label (bottom tab bar). From `md` on: a sidebar row.
  tab: "flex-col gap-1 px-1 py-2 text-tab md:flex-row md:gap-3 md:px-3 md:text-body",
};

export interface NavItemBaseProps extends AriaLinkProps {
  variant?: NavItemVariant;
}

/**
 * A navigation item. The router marks the current page with `aria-current="page"`, which React
 * Aria exposes as `data-current`: the current item gets a soft accent background and bold text
 * (not colour alone: the weight changes too).
 */
function NavItemBase({ className, variant = "row", ...props }: NavItemBaseProps) {
  return (
    <AriaLink
      {...props}
      className={joinClasses(
        `flex items-center rounded font-medium text-muted transition-colors ${navLayouts[variant]} ` +
          "data-hovered:bg-sunken data-hovered:text-fg " +
          "data-current:bg-accent-soft data-current:font-semibold data-current:text-fg " +
          focusRing,
        className,
      )}
    />
  );
}

/** A router link styled as text (typed `to`, preloading, client-side navigation). */
export const TextLink = createLink(TextLinkBase);

/** A router link styled as a navigation item. */
export const NavItem = createLink(NavItemBase);

export interface ExternalLinkProps extends Omit<AriaLinkProps, "href" | "target" | "rel"> {
  href: string;
}

/**
 * A text link to another site: opens in a new tab, sends no referrer, gives the page no handle on
 * Jofi's window and passes no endorsement (`noopener noreferrer nofollow`).
 */
export function ExternalLink(props: ExternalLinkProps) {
  return <TextLinkBase {...props} target="_blank" rel="noopener noreferrer nofollow" />;
}

/**
 * A text link that another app on the device handles, such as `mailto:` (mail) or `tel:` (phone):
 * same tab, no router. Build the `href` with a helper that encodes untrusted parts; never concatenate.
 */
export function AppLink(props: ExternalLinkProps) {
  return <TextLinkBase {...props} />;
}
