// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, type SyntheticEvent, useState } from "react";
import { m } from "../../paraglide/messages.js";
import { Button, Form, SegmentedControl, Select, TextArea, TextField } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import {
  BUCKETS,
  bucketLabels,
  FIELD_NAMES,
  LINK_TYPES,
  linkTypeLabels,
  MAX_NOTES_LENGTH,
  MAX_TITLE_LENGTH,
  type TaskFormValues,
  type TaskLinkType,
  type TimingChoice,
} from "./task";
import { useLinkChoices } from "./taskLinks";

export interface TaskFormProps {
  initial: TaskFormValues;
  fieldErrors: FieldErrors;
  /** Form-level messages (a failure, a version conflict), shown above the fields. */
  feedback?: ReactNode;
  submitLabel: string;
  isPending: boolean;
  onSubmit: (values: TaskFormValues) => void;
  onCancel: () => void;
}

const NO_LINK = "NONE";

/**
 * Every detail of a task (spec §10.2): title, Markdown notes, when (a bucket or an exact date and time in a
 * zone) and optionally what it is about. Server field errors come in through `fieldErrors`, named like the
 * request (`timing.localDue`, `link.id`).
 */
export function TaskForm({
  initial,
  fieldErrors,
  feedback,
  submitLabel,
  isPending,
  onSubmit,
  onCancel,
}: TaskFormProps) {
  const [values, setValues] = useState(initial);
  const [timingMissing, setTimingMissing] = useState(false);
  const set =
    <K extends keyof TaskFormValues>(key: K, name?: string) =>
    (value: TaskFormValues[K]) => {
      const update = () => setValues((current) => ({ ...current, [key]: value }));
      if (name) fieldErrors.clearing(name, update)(value);
      else update();
    };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (values.timing === "") {
      setTimingMissing(true);
      return;
    }
    // A Select has no native "required": a chosen kind without a record would silently drop the link.
    if (values.linkType !== "" && values.linkId === "") {
      fieldErrors.set({ ...fieldErrors.errors, [FIELD_NAMES.link]: m.task_violation_link_required() });
      return;
    }
    onSubmit(values);
  };
  const timingError =
    fieldErrors.errors[FIELD_NAMES.timing] ??
    fieldErrors.errors[FIELD_NAMES.timeZone] ??
    (timingMissing ? m.task_violation_timing() : undefined);

  return (
    <Form
      onSubmit={submit}
      validationErrors={fieldErrors.errors}
      className="flex max-w-2xl flex-col gap-5 rounded border border-line bg-surface p-6 shadow-card"
    >
      {feedback}
      <TextField
        name={FIELD_NAMES.title}
        label={m.task_field_title()}
        value={values.title}
        onChange={set("title", FIELD_NAMES.title)}
        maxLength={MAX_TITLE_LENGTH}
        validate={(value) => (value.trim() === "" ? m.company_violation_required() : null)}
      />
      <TimingFields
        values={values}
        error={timingError}
        onTiming={(timing) => {
          setTimingMissing(false);
          set("timing")(timing);
        }}
        onLocalDue={set("localDue", FIELD_NAMES.localDue)}
      />
      <LinkFields
        values={values}
        onType={set("linkType", FIELD_NAMES.link)}
        onId={set("linkId", FIELD_NAMES.link)}
      />
      <TextArea
        name={FIELD_NAMES.notes}
        label={m.task_field_notes()}
        description={m.company_field_markdown_hint()}
        value={values.notes}
        onChange={set("notes", FIELD_NAMES.notes)}
        maxLength={MAX_NOTES_LENGTH}
        rows={5}
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

interface TimingFieldsProps {
  values: TaskFormValues;
  error: string | undefined;
  onTiming: (timing: TimingChoice) => void;
  onLocalDue: (localDue: string) => void;
}

function TimingFields({ values, error, onTiming, onLocalDue }: TimingFieldsProps) {
  return (
    <div className="flex flex-col gap-3">
      <SegmentedControl<TimingChoice>
        label={m.task_field_when()}
        value={values.timing}
        onChange={onTiming}
        options={[
          ...BUCKETS.map((bucket) => ({ value: bucket, label: bucketLabels[bucket]() })),
          { value: "EXACT" as const, label: m.task_timing_choice_exact() },
        ]}
      />
      {error ? <p className="font-medium text-bad text-body">{error}</p> : null}
      {values.timing === "EXACT" ? (
        <TextField
          name={FIELD_NAMES.localDue}
          type="datetime-local"
          label={m.task_field_due()}
          description={m.task_field_due_zone({ zone: values.timeZone })}
          value={values.localDue}
          onChange={onLocalDue}
          validate={(value) => (value === "" ? m.task_violation_due_required() : null)}
          className="sm:w-72"
        />
      ) : null}
    </div>
  );
}

interface LinkFieldsProps {
  values: TaskFormValues;
  onType: (type: TaskLinkType | "") => void;
  onId: (id: string) => void;
}

function LinkFields({ values, onType, onId }: LinkFieldsProps) {
  const options = useLinkChoices(values.linkType, values.linkId);
  return (
    <div className="flex flex-col gap-3">
      <SegmentedControl<TaskLinkType | typeof NO_LINK>
        label={m.task_field_link()}
        value={values.linkType === "" ? NO_LINK : values.linkType}
        onChange={(type) => {
          onType(type === NO_LINK ? "" : type);
          onId("");
        }}
        options={[
          { value: NO_LINK, label: m.task_link_none() },
          ...LINK_TYPES.map((type) => ({ value: type, label: linkTypeLabels[type]() })),
        ]}
      />
      {values.linkType === "" ? null : (
        <Select
          name={FIELD_NAMES.link}
          label={linkTypeLabels[values.linkType]()}
          placeholder={m.task_link_choose()}
          value={values.linkId || null}
          onChange={onId}
          groups={[{ id: "choices", options }]}
          className="sm:w-96"
        />
      )}
    </div>
  );
}
