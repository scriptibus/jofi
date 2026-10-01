// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueries } from "@tanstack/react-query";
import { type ReactNode, type SyntheticEvent, useId, useState } from "react";
import {
  type ApplicationResponse,
  type ContactResponse,
  getGetContactQueryOptions,
  type InterviewRequest,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Button, CloseIcon, DateTimeField, Form, Select, TextArea } from "../../ui";
import type { FieldErrors } from "../auth/useFieldErrors";
import { ContactPicker, type PickerTexts } from "./ContactPicker";
import {
  type InterviewFormValues,
  MAX_PARTICIPANTS,
  timeZoneChoices,
  toInterviewRequest,
} from "./interviews";
import { type InterviewOutcome, interviewResultLabels, interviewTypeLabels, optionsOf } from "./labels";

/** The outcome option for "not decided yet" (a Select needs an id). */
const OPEN = "OPEN";
const NOTES_MAX = 50_000;

export interface InterviewFormProps {
  application: ApplicationResponse;
  initial: InterviewFormValues;
  /** Names the form, e.g. "Log an interview or call". */
  title: string;
  fieldErrors: FieldErrors;
  /** Form-level messages (a failure, a version conflict), shown above the fields. */
  feedback?: ReactNode;
  submitLabel: string;
  isPending: boolean;
  onSubmit: (request: InterviewRequest) => void;
  onCancel: () => void;
}

/**
 * An interview's or call's details for log and edit (spec §6.1): type, the agreed wall-clock time and the zone
 * it was agreed in (ADR-0048), participants from the contacts, notes before and after, and the outcome.
 */
export function InterviewForm(props: InterviewFormProps) {
  const { application, initial, title, fieldErrors, feedback, submitLabel, isPending, onSubmit, onCancel } =
    props;
  const headingId = useId();
  const [values, setValues] = useState(initial);
  const field = <K extends keyof InterviewFormValues>(name: K) =>
    fieldErrors.clearing(name, (value: InterviewFormValues[K]) =>
      setValues((current) => ({ ...current, [name]: value })),
    );

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const { localStart } = values;
    if (localStart === null) {
      fieldErrors.set({ ...fieldErrors.errors, localStart: m.application_interview_error_start_required() });
      return;
    }
    onSubmit(toInterviewRequest({ ...values, localStart }));
  };

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded border border-line bg-bg p-4">
      <h3 id={headingId} className="font-display text-h3">
        {title}
      </h3>
      <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-5">
        {feedback}
        <Select
          label={m.application_interview_field_type()}
          placeholder={m.application_interview_field_type()}
          groups={[{ id: "types", options: optionsOf(interviewTypeLabels) }]}
          value={values.type}
          onChange={(id) => field("type")(id as InterviewFormValues["type"])}
        />
        <div className="grid gap-5 md:grid-cols-2">
          <DateTimeField
            name="localStart"
            label={m.application_interview_field_start()}
            description={m.application_interview_field_start_hint()}
            value={values.localStart}
            onChange={field("localStart")}
          />
          <Select
            name="timeZone"
            label={m.application_interview_field_zone()}
            placeholder={m.application_interview_field_zone()}
            groups={[
              { id: "zones", options: timeZoneChoices(values.timeZone).map((id) => ({ id, label: id })) },
            ]}
            value={values.timeZone}
            onChange={field("timeZone")}
          />
        </div>
        <ParticipantsField
          application={application}
          ids={values.participantIds}
          error={fieldErrors.errors.participantIds}
          onChange={field("participantIds")}
        />
        <TextArea
          name="preparationNotes"
          label={m.application_interview_field_preparation()}
          description={m.application_interview_field_markdown_hint()}
          value={values.preparationNotes}
          onChange={field("preparationNotes")}
          maxLength={NOTES_MAX}
        />
        <TextArea
          name="notes"
          label={m.application_interview_field_notes()}
          description={m.application_interview_field_markdown_hint()}
          value={values.notes}
          onChange={field("notes")}
          maxLength={NOTES_MAX}
        />
        <Select
          label={m.application_interview_field_outcome()}
          placeholder={m.application_interview_field_outcome()}
          groups={[
            {
              id: "outcomes",
              options: [
                { id: OPEN, label: m.application_interview_outcome_open() },
                ...optionsOf(interviewResultLabels),
              ],
            },
          ]}
          value={values.outcome ?? OPEN}
          onChange={(id) => field("outcome")(id === OPEN ? null : (id as InterviewOutcome))}
        />
        <div className="flex flex-wrap justify-end gap-3">
          <Button variant="secondary" onPress={onCancel}>
            {m.confirm_cancel()}
          </Button>
          <Button type="submit" isDisabled={isPending}>
            {submitLabel}
          </Button>
        </div>
      </Form>
    </section>
  );
}

const participantTexts = (): PickerTexts => ({
  title: m.application_interview_participants_add(),
  pickNamed: (name) => m.application_interview_participant_add_named({ name }),
  pick: m.application_interview_participant_add_short(),
});

interface ParticipantsFieldProps {
  application: ApplicationResponse;
  ids: string[];
  /** The server's word on the participants (gone or too many). */
  error: string | undefined;
  onChange: (ids: string[]) => void;
}

/** Who took part: chosen from the contacts with the picker, removed one by one. */
function ParticipantsField({ application, ids, error, onChange }: ParticipantsFieldProps) {
  const [picking, setPicking] = useState(false);
  const contacts = useQueries({
    queries: ids.map((id) =>
      getGetContactQueryOptions(id, { query: { meta: { errorHandledLocally: true } } }),
    ),
  });
  const nameOf = (position: number) => {
    const contact = contacts[position];
    if (contact?.data) return contact.data.name;
    return contact?.isError ? m.application_contacts_unknown() : m.loading();
  };
  const pick = (contact: ContactResponse) => {
    setPicking(false);
    onChange([...ids, contact.id]);
  };
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1.5 font-semibold text-body">
        {m.application_interview_field_participants()}
      </legend>
      {ids.length === 0 ? (
        <p className="text-muted">{m.application_interview_participants_none()}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {ids.map((id, position) => (
            <li
              key={id}
              className="flex items-center gap-1 rounded border border-line bg-surface py-1 pr-1 pl-3"
            >
              <span className="break-words">{nameOf(position)}</span>
              <Button
                variant="secondary"
                className="border-transparent px-2 py-1"
                aria-label={m.application_interview_participant_remove({ name: nameOf(position) })}
                onPress={() => onChange(ids.filter((other) => other !== id))}
              >
                <CloseIcon className="size-4" aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      {error ? <p className="font-medium text-bad text-body">{error}</p> : null}
      <Button
        variant="secondary"
        className="self-start"
        isDisabled={ids.length >= MAX_PARTICIPANTS}
        onPress={() => setPicking(true)}
      >
        <AddIcon className="size-4" aria-hidden="true" />
        {m.application_interview_participants_add()}
      </Button>
      <ContactPicker
        application={application}
        isOpen={picking}
        onClose={() => setPicking(false)}
        onPick={pick}
        excludeIds={ids}
        texts={participantTexts()}
        allowCreate={false}
      />
    </fieldset>
  );
}
