// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { m } from "../../paraglide/messages.js";
import { NumberField, SegmentedControl, Select } from "../../ui";
import type { ApplicationFormValues } from "./application";
import { optionsOf } from "./labels";

/** The change handler of one form field; editing a field also drops its server error. */
export type FieldSetter = <K extends keyof ApplicationFormValues>(
  key: K,
) => (value: ApplicationFormValues[K]) => void;

/** A group of the application form: the form's values and their setters. */
export interface SectionProps {
  values: ApplicationFormValues;
  field: FieldSetter;
}

/** The option for "not set" (a list option or radio value cannot be empty). */
const UNSET = "UNSET";

interface ChoiceProps<T extends string> {
  label: string;
  labels: Record<T, () => string>;
  value: T | "";
  onChange: (value: T | "") => void;
}

/** One of an enum's values or none, as a list (for longer lists), with the server field `name`. */
export function ChoiceSelect<T extends string>({
  name,
  label,
  labels,
  value,
  onChange,
}: ChoiceProps<T> & { name: string }) {
  return (
    <Select
      name={name}
      label={label}
      placeholder={m.company_value_none()}
      value={value === "" ? UNSET : value}
      onChange={(id) => onChange(id === UNSET ? "" : (id as T))}
      groups={[
        { id: "unset", options: [{ id: UNSET, label: m.company_value_none() }] },
        { id: "values", options: optionsOf(labels) },
      ]}
    />
  );
}

/** One of an enum's values or none, as segments (for a handful of short words). */
export function ChoiceSegments<T extends string>({
  label,
  labels,
  value,
  onChange,
  lang,
}: ChoiceProps<T> & { lang?: Partial<Record<T, string>> }) {
  return (
    <SegmentedControl<T | typeof UNSET>
      label={label}
      value={value === "" ? UNSET : value}
      onChange={(next) => onChange(next === UNSET ? "" : (next as T))}
      options={[
        { value: UNSET, label: m.company_value_none() },
        ...optionsOf(labels).map(({ id, label: text }) => ({
          value: id,
          label: text,
          ...(lang?.[id] ? { lang: lang[id] } : {}),
        })),
      ]}
    />
  );
}

interface AmountProps {
  name: string;
  label: string;
  value: number;
  onChange: (value: number) => void;
  /** Largest allowed value; amounts of money have none below the server's. */
  max?: number;
  /** Decimals allowed: 2 for money, 0 for percentages and days. */
  decimals: 0 | 2;
  validate?: (value: number) => string | null;
  description?: ReactNode;
}

/** A number in the user's locale, empty for "not set" (`NaN`), never below zero. */
export function AmountField({
  name,
  label,
  value,
  onChange,
  max,
  decimals,
  validate,
  description,
}: AmountProps) {
  return (
    <NumberField
      name={name}
      label={label}
      value={value}
      onChange={onChange}
      minValue={0}
      {...(max === undefined ? {} : { maxValue: max })}
      formatOptions={{ maximumFractionDigits: decimals }}
      {...(validate ? { validate } : {})}
      {...(description ? { description } : {})}
    />
  );
}

/** A group of fields with a visible caption, e.g. "Pay band". */
export function FieldGroup({
  legend,
  hint,
  children,
}: {
  legend: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <fieldset className="flex flex-col gap-4 border-line border-t pt-5">
      <legend className="pb-2 font-display text-h3">{legend}</legend>
      {hint ? <p className="text-muted">{hint}</p> : null}
      {children}
    </fieldset>
  );
}
