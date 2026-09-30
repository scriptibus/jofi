// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  TextField as AriaTextField,
  type TextFieldProps as AriaTextFieldProps,
  FieldError,
  Input,
  Label,
  Text,
} from "react-aria-components";

export interface TextFieldProps extends Omit<AriaTextFieldProps, "children" | "className"> {
  /** Visible label; also the accessible name of the input. */
  label: string;
  /** Help text below the input, linked with `aria-describedby`. */
  description?: ReactNode;
  /** Monospace input, e.g. for tokens. */
  mono?: boolean;
  className?: string;
}

/**
 * A labelled single-line input with help text and validation errors (React Aria TextField).
 * Errors come from `validate`, native constraints (`isRequired`, `minLength`) or a surrounding
 * `Form`'s `validationErrors` (server errors), and show after submit or blur.
 */
export function TextField({ label, description, mono = false, className, ...props }: TextFieldProps) {
  return (
    <AriaTextField {...props} className={["flex flex-col gap-1.5", className].filter(Boolean).join(" ")}>
      <Label className="font-semibold text-body">{label}</Label>
      <Input
        className={
          "w-full rounded border border-line bg-surface px-3 py-2 text-body text-fg transition-colors " +
          "placeholder:text-muted data-hovered:border-muted " +
          "data-focused:border-accent data-focused:outline-2 data-focused:outline-offset-1 data-focused:outline-accent " +
          "data-invalid:border-bad data-disabled:opacity-50 " +
          (mono ? "font-data" : "")
        }
      />
      {description ? (
        <Text slot="description" className="text-muted text-body">
          {description}
        </Text>
      ) : null}
      <FieldError className="font-medium text-bad text-body" />
    </AriaTextField>
  );
}
