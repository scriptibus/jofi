// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  Menu as AriaMenu,
  Button,
  Header,
  MenuItem,
  MenuSection,
  MenuTrigger,
  Popover,
} from "react-aria-components";

export interface MenuAction {
  id: string;
  label: string;
}

export interface MenuGroup {
  id: string;
  /** Visible heading of the group inside the menu; without one, the actions stand alone. */
  title?: string;
  actions: readonly MenuAction[];
}

export interface MenuButtonProps {
  /** The button's accessible name; also names the menu. */
  label: string;
  /** What the button shows: an icon (hidden from assistive technology) or text. */
  children: ReactNode;
  groups: readonly MenuGroup[];
  onAction: (id: string) => void;
  isDisabled?: boolean;
}

/**
 * A button that opens a menu of actions in a popover (React Aria Menu: `menu`/`menuitem`, arrow keys,
 * type-ahead, Escape closes and focus returns to the button). Empty groups are left out.
 */
export function MenuButton({ label, children, groups, onAction, isDisabled }: MenuButtonProps) {
  const shown = groups.filter((group) => group.actions.length > 0);
  return (
    <MenuTrigger>
      <Button
        aria-label={label}
        isDisabled={isDisabled ?? false}
        className={
          "inline-flex cursor-default items-center justify-center gap-2 rounded border border-line bg-surface " +
          "px-2 py-1.5 text-body text-fg data-hovered:border-fg data-disabled:opacity-50 " +
          "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
        }
      >
        {children}
      </Button>
      <Popover className="max-h-80 min-w-48 max-w-prose overflow-auto rounded border border-line bg-surface p-1 text-fg shadow-card">
        <AriaMenu
          aria-label={label}
          onAction={(id) => {
            if (typeof id === "string") onAction(id);
          }}
          className="outline-none"
        >
          {shown.map((group) => (
            <MenuSection key={group.id} id={group.id}>
              {group.title ? (
                <Header className="px-2 pt-2 pb-1 font-data text-eyebrow text-muted uppercase">
                  {group.title}
                </Header>
              ) : null}
              {group.actions.map((action) => (
                <MenuItem
                  key={action.id}
                  id={action.id}
                  textValue={action.label}
                  className="flex cursor-default items-center gap-2 rounded px-2 py-1.5 text-body outline-none data-focused:bg-sunken"
                >
                  {action.label}
                </MenuItem>
              ))}
            </MenuSection>
          ))}
        </AriaMenu>
      </Popover>
    </MenuTrigger>
  );
}
