// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, type SyntheticEvent, useState } from "react";
import { m } from "../../paraglide/messages.js";
import { Button, Form, Select, TextArea, TextField } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import { ChannelsEditor } from "./ChannelsEditor";
import { useCompanyChoices } from "./companyChoices";
import type { ContactFormValues } from "./contact";

export interface ContactFormProps {
  initial: ContactFormValues;
  fieldErrors: FieldErrors;
  /** Form-level messages (a failure, a version conflict), shown above the fields. */
  feedback?: ReactNode;
  submitLabel: string;
  isPending: boolean;
  onSubmit: (values: ContactFormValues) => void;
  onCancel: () => void;
}

/** The company option for "none" (a list option cannot have an empty id). */
const NO_COMPANY = "none";

/** The contact's details and channels for create and edit. Server field errors come in through `fieldErrors`. */
export function ContactForm({
  initial,
  fieldErrors,
  feedback,
  submitLabel,
  isPending,
  onSubmit,
  onCancel,
}: ContactFormProps) {
  const [values, setValues] = useState(initial);
  const { choices } = useCompanyChoices(values.companyId || undefined);
  const field = <K extends keyof ContactFormValues>(name: K) =>
    fieldErrors.clearing(name, (value: ContactFormValues[K]) =>
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
        label={m.contact_field_name()}
        value={values.name}
        onChange={field("name")}
        maxLength={200}
        validate={(value) => (value.trim() === "" ? m.company_violation_required() : null)}
      />
      <TextField
        name="role"
        label={m.contact_field_role()}
        description={m.contact_field_role_hint()}
        value={values.role}
        onChange={field("role")}
        maxLength={200}
      />
      <Select
        name="companyId"
        label={m.contact_field_company()}
        placeholder={m.contact_company_none()}
        value={values.companyId || NO_COMPANY}
        onChange={(id) => field("companyId")(id === NO_COMPANY ? "" : id)}
        groups={[
          { id: "no-company", options: [{ id: NO_COMPANY, label: m.contact_company_none() }] },
          {
            id: "companies",
            title: m.nav_companies(),
            options: choices.map(({ id, name }) => ({ id, label: name })),
          },
        ]}
      />
      <ChannelsEditor
        channels={values.channels}
        onChange={(channels) => setValues((current) => ({ ...current, channels }))}
        fieldErrors={fieldErrors}
      />
      <TextArea
        name="relationshipNotes"
        label={m.contact_field_notes()}
        description={m.contact_field_notes_hint()}
        value={values.relationshipNotes}
        onChange={field("relationshipNotes")}
        rows={6}
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
