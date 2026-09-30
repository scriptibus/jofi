// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  Select as AriaSelect,
  Button,
  Header,
  Label,
  ListBox,
  ListBoxItem,
  ListBoxSection,
  Popover,
  SelectValue,
  Text,
} from "react-aria-components";
import { ChevronDownIcon } from "./icons";

export interface SelectOption {
  id: string;
  label: string;
}

export interface SelectGroup {
  id: string;
  /** Visible heading of the group inside the list. */
  title: string;
  options: readonly SelectOption[];
}

export interface SelectProps {
  /** Visible label; also the accessible name of the button and the list. */
  label: string;
  groups: readonly SelectGroup[];
  /** The selected option's id, or null when nothing is chosen yet. */
  value: string | null;
  onChange: (id: string) => void;
  placeholder: string;
  /** Help text below the button, linked with `aria-describedby`. */
  description?: ReactNode;
  isDisabled?: boolean;
  className?: string;
}

/**
 * A single choice from a list in a popover, grouped under headings (React Aria Select): a button
 * showing the choice, arrow keys and type-ahead in the list. Use `SegmentedControl` for a handful of
 * options that fit on one line.
 */
export function Select({
  label,
  groups,
  value,
  onChange,
  placeholder,
  description,
  isDisabled,
  className,
}: SelectProps) {
  return (
    <AriaSelect
      value={value}
      onChange={(id) => {
        if (typeof id === "string") onChange(id);
      }}
      placeholder={placeholder}
      isDisabled={isDisabled ?? false}
      className={["flex flex-col gap-1.5", className].filter(Boolean).join(" ")}
    >
      <Label className="font-semibold text-body">{label}</Label>
      <Button
        className={
          "flex w-full items-center justify-between gap-2 rounded border border-line bg-surface px-3 py-2 " +
          "text-left text-body text-fg data-hovered:border-muted data-disabled:opacity-50 " +
          "data-focus-visible:outline-2 data-focus-visible:outline-offset-1 data-focus-visible:outline-accent"
        }
      >
        <SelectValue className="min-w-0 truncate data-placeholder:text-muted" />
        <ChevronDownIcon className="size-4 shrink-0 text-muted" aria-hidden="true" />
      </Button>
      {description ? (
        <Text slot="description" className="text-muted text-body">
          {description}
        </Text>
      ) : null}
      <Popover className="max-h-80 min-w-64 max-w-prose overflow-auto rounded border border-line bg-surface p-1 text-fg shadow-card">
        <ListBox className="outline-none">
          {groups.map((group) => (
            <ListBoxSection key={group.id} id={group.id}>
              <Header className="px-2 pt-2 pb-1 font-data text-eyebrow text-muted uppercase">
                {group.title}
              </Header>
              {group.options.map((option) => (
                <ListBoxItem
                  key={option.id}
                  id={option.id}
                  textValue={option.label}
                  className={
                    "cursor-default rounded px-2 py-1.5 text-body outline-none " +
                    "data-focused:bg-sunken data-selected:font-semibold"
                  }
                >
                  {option.label}
                </ListBoxItem>
              ))}
            </ListBoxSection>
          ))}
        </ListBox>
      </Popover>
    </AriaSelect>
  );
}
