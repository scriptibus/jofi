// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Button as AriaButton,
  type ButtonProps as AriaButtonProps,
  composeRenderProps,
} from "react-aria-components";

export type ButtonVariant = "primary" | "secondary";

export interface ButtonProps extends AriaButtonProps {
  /** `primary`: accent fill, one per view. `secondary`: quiet outline. */
  variant?: ButtonVariant;
}

const base =
  "inline-flex items-center justify-center gap-2 rounded border px-4 py-2 font-semibold text-body " +
  "cursor-default select-none transition-spring " +
  "data-hovered:-translate-y-0.5 data-pressed:translate-y-0 data-pressed:scale-98 " +
  "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent " +
  "data-disabled:cursor-not-allowed data-disabled:opacity-50 data-disabled:translate-y-0";

const variants: Record<ButtonVariant, string> = {
  primary: "border-transparent bg-accent text-accent-fg shadow-card",
  secondary: "border-line bg-surface text-fg data-hovered:border-fg",
};

/** Our button: React Aria behaviour (press, keyboard, focus) with Stall styling. */
export function Button({ variant = "primary", className, ...props }: ButtonProps) {
  return (
    <AriaButton
      {...props}
      className={composeRenderProps(className, (custom) =>
        [base, variants[variant], custom].filter(Boolean).join(" "),
      )}
    />
  );
}
