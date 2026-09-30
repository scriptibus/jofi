// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import {
  NumberField as AriaNumberField,
  type NumberFieldProps as AriaNumberFieldProps,
  FieldError,
  Input,
  Label,
  Text,
} from "react-aria-components";

export interface NumberFieldProps extends Omit<AriaNumberFieldProps, "children" | "className"> {
  /** Visible label; also the accessible name of the input. */
  label: string;
  /** Help text below the input, linked with `aria-describedby`. */
  description?: ReactNode;
  className?: string;
}

/**
 * A labelled number input that reads and writes numbers in the user's locale (React Aria NumberField):
 * `1.234,50` in German, `1,234.50` in English, with `formatOptions` such as a currency. Empty is `NaN`.
 */
export function NumberField({ label, description, className, ...props }: NumberFieldProps) {
  return (
    <AriaNumberField {...props} className={["flex flex-col gap-1.5", className].filter(Boolean).join(" ")}>
      <Label className="font-semibold text-body">{label}</Label>
      <Input
        className={
          "w-full rounded border border-line bg-surface px-3 py-2 font-data text-body text-fg transition-colors " +
          "data-hovered:border-muted data-focused:border-accent data-focused:outline-2 " +
          "data-focused:outline-offset-1 data-focused:outline-accent data-invalid:border-bad data-disabled:opacity-50"
        }
      />
      {description ? (
        <Text slot="description" className="text-muted text-body">
          {description}
        </Text>
      ) : null}
      <FieldError className="font-medium text-bad text-body" />
    </AriaNumberField>
  );
}
