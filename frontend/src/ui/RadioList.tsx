// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Label, RadioButton, RadioField, RadioGroup } from "react-aria-components";

export interface RadioListOption<T extends string> {
  value: T;
  /** The option's name, e.g. "Version 3". */
  label: string;
  /** More about the option under its name (dates, badges); read as part of the option. */
  details?: ReactNode;
}

export interface RadioListProps<T extends string> {
  /** Group label; also the accessible name of the radio group. */
  label: string;
  options: readonly RadioListOption<T>[];
  value: T | null;
  onChange: (value: T) => void;
  /** Keeps the label for assistive technology only, when a heading right above already says it. */
  hideLabel?: boolean;
}

/**
 * A single choice from a vertical list of rich options, e.g. versions with their dates (React Aria radio
 * group: arrow keys move, one tab stop). The chosen option has a marked border and a filled dot, never
 * colour alone. Use `SegmentedControl` for a handful of short options on one line.
 */
export function RadioList<T extends string>({
  label,
  options,
  value,
  onChange,
  hideLabel = false,
}: RadioListProps<T>) {
  return (
    <RadioGroup
      value={value}
      onChange={(next) => {
        const option = options.find((candidate) => candidate.value === next);
        if (option) onChange(option.value);
      }}
      className="flex flex-col gap-2"
    >
      <Label className={hideLabel ? "sr-only" : "font-data text-eyebrow text-muted uppercase"}>{label}</Label>
      <div className="flex flex-col gap-2">
        {options.map((option) => (
          <RadioField key={option.value} value={option.value} className="flex">
            <RadioButton
              className={
                "group flex flex-1 cursor-default items-start gap-3 rounded border border-line bg-surface px-3 py-2 " +
                "text-body data-hovered:border-muted data-selected:border-fg data-selected:shadow-card " +
                "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
              }
            >
              <span
                aria-hidden="true"
                className={
                  "mt-1 flex size-4 shrink-0 items-center justify-center rounded-full border-2 border-muted " +
                  "group-data-selected:border-fg"
                }
              >
                <span className="hidden size-2 rounded-full bg-fg group-data-selected:block" />
              </span>
              <span className="flex min-w-0 flex-col gap-1">
                <span className="font-semibold group-data-selected:underline">{option.label}</span>
                {option.details ? <span className="text-muted">{option.details}</span> : null}
              </span>
            </RadioButton>
          </RadioField>
        ))}
      </div>
    </RadioGroup>
  );
}
