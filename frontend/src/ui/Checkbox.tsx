// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { CheckboxButton, CheckboxField } from "react-aria-components";
import { CheckIcon } from "./icons";

export interface CheckboxProps {
  /** Visible label; also the accessible name of the checkbox. */
  children: ReactNode;
  isSelected: boolean;
  onChange: (isSelected: boolean) => void;
  isDisabled?: boolean;
  className?: string;
}

/**
 * A labelled checkbox (React Aria CheckboxField + CheckboxButton: a native input, Space toggles). The box
 * shows a check mark when selected, so the state never relies on colour alone.
 */
export function Checkbox({ children, isSelected, onChange, isDisabled, className }: CheckboxProps) {
  return (
    <CheckboxField
      isSelected={isSelected}
      onChange={onChange}
      isDisabled={isDisabled ?? false}
      {...(className ? { className } : {})}
    >
      <CheckboxButton className="group flex cursor-default items-start gap-3 text-body text-fg">
        {({ isSelected: selected }) => (
          <>
            <span
              aria-hidden="true"
              className={
                "mt-0.5 flex size-5 shrink-0 items-center justify-center rounded border-2 border-muted bg-surface " +
                "group-data-hovered:border-fg group-data-selected:border-accent group-data-selected:bg-accent " +
                "group-data-selected:text-accent-fg group-data-disabled:opacity-50 " +
                "group-data-focus-visible:outline-2 group-data-focus-visible:outline-offset-2 " +
                "group-data-focus-visible:outline-accent"
              }
            >
              {selected ? <CheckIcon className="size-4" strokeWidth={3} /> : null}
            </span>
            <span className="min-w-0 break-words">{children}</span>
          </>
        )}
      </CheckboxButton>
    </CheckboxField>
  );
}
