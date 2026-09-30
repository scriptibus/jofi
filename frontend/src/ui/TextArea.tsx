// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  TextArea as AriaTextArea,
  TextField as AriaTextField,
  type TextFieldProps as AriaTextFieldProps,
  FieldError,
  Label,
  Text,
} from "react-aria-components";

export interface TextAreaProps extends Omit<AriaTextFieldProps, "children" | "className"> {
  /** Visible label; also the accessible name of the text area. */
  label: string;
  /** Help text below the text area, linked with `aria-describedby`. */
  description?: ReactNode;
  /** Visible lines before it scrolls. */
  rows?: number;
  className?: string;
}

/**
 * A labelled multi-line input with help text and validation errors, like `TextField` (the same
 * `validate`, `isRequired` and `Form` `validationErrors` by `name`).
 */
export function TextArea({ label, description, rows = 4, className, ...props }: TextAreaProps) {
  return (
    <AriaTextField {...props} className={["flex flex-col gap-1.5", className].filter(Boolean).join(" ")}>
      <Label className="font-semibold text-body">{label}</Label>
      <AriaTextArea
        rows={rows}
        className={
          "w-full resize-y rounded border border-line bg-surface px-3 py-2 text-body text-fg transition-colors " +
          "placeholder:text-muted data-hovered:border-muted " +
          "data-focused:border-accent data-focused:outline-2 data-focused:outline-offset-1 data-focused:outline-accent " +
          "data-invalid:border-bad data-disabled:opacity-50"
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
