// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, type SyntheticEvent, useState } from "react";
import { m } from "../../paraglide/messages.js";
import { Button, Form, SegmentedControl, TextArea, TextField } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import { type CompanyFormValues, type CompanySize, isWebAddress, SIZES, sizeLabel } from "./company";

export interface CompanyFormProps {
  initial: CompanyFormValues;
  fieldErrors: FieldErrors;
  /** Form-level messages (a failure, a version conflict), shown above the fields. */
  feedback?: ReactNode;
  submitLabel: string;
  isPending: boolean;
  onSubmit: (values: CompanyFormValues) => void;
  onCancel: () => void;
}

/** The size option for "not set" (a radio value cannot be empty). */
const UNKNOWN_SIZE = "UNKNOWN";

const webAddress = (value: string) =>
  value.trim() === "" || isWebAddress(value) ? null : m.company_violation_invalid_url();

/** The company's details for create and edit. Server field errors come in through `fieldErrors`. */
export function CompanyForm({
  initial,
  fieldErrors,
  feedback,
  submitLabel,
  isPending,
  onSubmit,
  onCancel,
}: CompanyFormProps) {
  const [values, setValues] = useState(initial);
  const field = <K extends keyof CompanyFormValues>(name: K) =>
    fieldErrors.clearing(name, (value: CompanyFormValues[K]) =>
      setValues((current) => ({ ...current, [name]: value })),
    );

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit(values);
  };

  return (
    <Form
      onSubmit={submit}
      validationErrors={fieldErrors.errors}
      className="flex max-w-2xl flex-col gap-5 rounded border border-line bg-surface p-6 shadow-card"
    >
      {feedback}
      <TextField
        name="name"
        label={m.company_field_name()}
        value={values.name}
        onChange={field("name")}
        maxLength={200}
        validate={(value) => (value.trim() === "" ? m.company_violation_required() : null)}
      />
      <TextField
        name="website"
        inputMode="url"
        label={m.company_field_website()}
        description={m.company_field_url_hint()}
        value={values.website}
        onChange={field("website")}
        validate={webAddress}
      />
      <TextField
        name="careersPage"
        inputMode="url"
        label={m.company_field_careers_page()}
        description={m.company_field_url_hint()}
        value={values.careersPage}
        onChange={field("careersPage")}
        validate={webAddress}
      />
      <TextField
        name="industry"
        label={m.company_field_industry()}
        value={values.industry}
        onChange={field("industry")}
        maxLength={200}
      />
      <SegmentedControl<CompanySize | typeof UNKNOWN_SIZE>
        label={m.company_field_size()}
        value={values.size === "" ? UNKNOWN_SIZE : values.size}
        onChange={(size) => field("size")(size === UNKNOWN_SIZE ? "" : size)}
        options={[
          { value: UNKNOWN_SIZE, label: m.company_size_unknown() },
          ...SIZES.map((size) => ({ value: size, label: sizeLabel(size) })),
        ]}
      />
      <TextArea
        name="locations"
        label={m.company_field_locations()}
        description={m.company_field_locations_hint()}
        value={values.locations}
        onChange={field("locations")}
        rows={3}
      />
      <TextArea
        name="researchNotes"
        label={m.company_field_research_notes()}
        description={m.company_field_markdown_hint()}
        value={values.researchNotes}
        onChange={field("researchNotes")}
        rows={8}
      />
      <div className="flex flex-wrap gap-3">
        <Button variant="secondary" onPress={onCancel}>
          {m.confirm_cancel()}
        </Button>
        <Button type="submit" isDisabled={isPending}>
          {submitLabel}
        </Button>
      </div>
    </Form>
  );
}
