// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, type SyntheticEvent, useState } from "react";
import { m } from "../../paraglide/messages.js";
import { Button, Form, Select, TextArea, TextField } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import { useCompanyChoices } from "../contacts/companyChoices";
import { LanguageFields, OfferFields, PayBandFields } from "./ApplicationFormSections";
import { type ApplicationFormValues, FIELD_NAMES } from "./application";
import {
  AmountField,
  ChoiceSegments,
  ChoiceSelect,
  FieldGroup,
  type FieldSetter,
  type SectionProps,
} from "./formFields";
import { employmentTypeLabels, howAppliedLabels, seniorityLabels } from "./labels";

export interface ApplicationFormProps {
  initial: ApplicationFormValues;
  fieldErrors: FieldErrors;
  /** Form-level messages (a failure, a version conflict), shown above the fields. */
  feedback?: ReactNode;
  submitLabel: string;
  isPending: boolean;
  onSubmit: (values: ApplicationFormValues) => void;
  onCancel: () => void;
}

const MAX_PERCENT = 100;

/**
 * Every detail of an application the user edits (spec §6.1), in groups: the job, applying, pay band,
 * language & tone, offer. Status and decline reason change through the status control, not here.
 * Server field errors come in through `fieldErrors`, named like the request (`payBand.max`).
 */
export function ApplicationForm({
  initial,
  fieldErrors,
  feedback,
  submitLabel,
  isPending,
  onSubmit,
  onCancel,
}: ApplicationFormProps) {
  const [values, setValues] = useState(initial);
  const field: FieldSetter = (key) =>
    fieldErrors.clearing(FIELD_NAMES[key], (value) => setValues((current) => ({ ...current, [key]: value })));

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
      <JobFields values={values} field={field} />
      <ApplyingFields values={values} field={field} />
      <PayBandFields values={values} field={field} />
      <LanguageFields values={values} field={field} />
      <OfferFields values={values} field={field} />
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

function JobFields({ values, field }: SectionProps) {
  const { choices } = useCompanyChoices(values.companyId || undefined);
  return (
    <FieldGroup legend={m.application_form_job()}>
      <TextField
        name={FIELD_NAMES.title}
        label={m.application_field_title()}
        value={values.title}
        onChange={field("title")}
        maxLength={300}
        validate={(value) => (value.trim() === "" ? m.company_violation_required() : null)}
      />
      <Select
        name={FIELD_NAMES.companyId}
        label={m.application_field_company()}
        placeholder={m.application_company_choose()}
        value={values.companyId || null}
        onChange={field("companyId")}
        groups={[{ id: "companies", options: choices.map(({ id, name }) => ({ id, label: name })) }]}
      />
      <TextField
        name={FIELD_NAMES.location}
        label={m.application_field_location()}
        value={values.location}
        onChange={field("location")}
        maxLength={200}
      />
      <AmountField
        name={FIELD_NAMES.remoteShare}
        label={m.application_field_remote_share_percent()}
        value={values.remoteShare}
        onChange={field("remoteShare")}
        max={MAX_PERCENT}
        decimals={0}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <ChoiceSelect
          name={FIELD_NAMES.employmentType}
          label={m.application_field_employment_type()}
          labels={employmentTypeLabels}
          value={values.employmentType}
          onChange={field("employmentType")}
        />
        <ChoiceSelect
          name={FIELD_NAMES.seniority}
          label={m.application_field_seniority()}
          labels={seniorityLabels}
          value={values.seniority}
          onChange={field("seniority")}
        />
      </div>
    </FieldGroup>
  );
}

function ApplyingFields({ values, field }: SectionProps) {
  return (
    <FieldGroup legend={m.application_applying_heading()}>
      <TextField
        name={FIELD_NAMES.deadline}
        type="date"
        label={m.application_field_deadline()}
        value={values.deadline}
        onChange={field("deadline")}
      />
      <ChoiceSegments
        label={m.application_field_how_applied()}
        labels={howAppliedLabels}
        value={values.howApplied}
        onChange={field("howApplied")}
      />
      <TextArea
        name={FIELD_NAMES.portalNotes}
        label={m.application_field_portal_notes()}
        description={m.company_field_markdown_hint()}
        value={values.portalNotes}
        onChange={field("portalNotes")}
        rows={5}
      />
    </FieldGroup>
  );
}
