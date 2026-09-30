// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Disclosure as AriaDisclosure, Button, DisclosurePanel } from "react-aria-components";
import { ChevronDownIcon } from "./icons";

export interface DisclosureProps {
  /** The trigger button's text. */
  label: string;
  /** Open on first render (e.g. when something inside is already set). */
  defaultExpanded?: boolean;
  children: ReactNode;
}

/**
 * A section that opens and closes with a button (React Aria Disclosure: `aria-expanded`, the panel
 * linked with `aria-controls`). For secondary content such as less-used filters.
 */
export function Disclosure({ label, defaultExpanded = false, children }: DisclosureProps) {
  return (
    <AriaDisclosure defaultExpanded={defaultExpanded} className="group flex flex-col gap-4">
      <Button
        slot="trigger"
        className={
          "inline-flex w-fit cursor-default items-center gap-2 rounded font-semibold text-body text-fg " +
          "data-hovered:underline " +
          "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
        }
      >
        <ChevronDownIcon
          className="size-4 shrink-0 motion-safe:transition-transform group-data-expanded:rotate-180"
          aria-hidden="true"
        />
        {label}
      </Button>
      <DisclosurePanel>{children}</DisclosurePanel>
    </AriaDisclosure>
  );
}
