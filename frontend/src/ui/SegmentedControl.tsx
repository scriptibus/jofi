// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Label, RadioButton, RadioField, RadioGroup } from "react-aria-components";

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  /** Optional decorative content shown before the label (e.g. a colour swatch). */
  icon?: ReactNode;
  /** BCP 47 language of the label, when it differs from the page (e.g. "Deutsch"). */
  lang?: string;
}

export interface SegmentedControlProps<T extends string> {
  /** Visible group label; also the accessible name of the radio group. */
  label: string;
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
}

/**
 * A single-choice control rendered as a row of segments.
 * Semantics: a React Aria radio group (arrow keys move, one tab stop).
 */
export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
}: SegmentedControlProps<T>) {
  return (
    <RadioGroup
      value={value}
      onChange={(next) => {
        const option = options.find((o) => o.value === next);
        if (option) onChange(option.value);
      }}
      orientation="horizontal"
      className="flex flex-col gap-2"
    >
      <Label className="font-data text-eyebrow text-muted uppercase">{label}</Label>
      <div className="flex w-fit max-w-full flex-wrap gap-1 rounded bg-sunken p-1">
        {options.map((option) => (
          <RadioField key={option.value} value={option.value} className="flex">
            <RadioButton
              lang={option.lang}
              className={
                "flex cursor-default items-center gap-2 rounded border border-transparent px-3 py-1.5 " +
                "font-medium text-muted text-body transition-spring " +
                "data-hovered:text-fg " +
                "data-selected:border-line data-selected:bg-surface data-selected:text-fg data-selected:shadow-card " +
                "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
              }
            >
              {option.icon}
              {option.label}
            </RadioButton>
          </RadioField>
        ))}
      </div>
    </RadioGroup>
  );
}

/** Decorative accent swatch; `accent` selects a preset via the data-accent token override. */
export function AccentSwatch({ accent }: { accent: string }) {
  return (
    <span
      aria-hidden="true"
      data-accent={accent}
      className="inline-block size-3 rounded-full border border-line bg-accent"
    />
  );
}
