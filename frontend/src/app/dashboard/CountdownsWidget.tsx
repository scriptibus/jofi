// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type SyntheticEvent, useRef, useState } from "react";
import type { DashboardCountdownResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Alert, Button, DeleteIcon, Form, TextField, TextLink } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { sectionCard } from "../companies/RelatedRecords";
import type { ErrorDescription } from "../problems";
import { taskFieldErrorsOf, viewerTimeZone } from "../tasks/task";
import {
  daysBetween,
  describeCountdownError,
  describeRemaining,
  describeTarget,
  MAX_TITLE_LENGTH,
  sourceLabels,
  targetDay,
} from "./countdowns";
import { useAddCountdown, useDashboardCountdowns, useDeleteCountdown } from "./useCountdowns";

const HEADING_ID = "countdowns-heading";

/** What the widget last has to say: a countdown added or deleted, or a failure. */
type Feedback = { kind: "added" | "deleted"; title: string } | { kind: "failure"; failure: ErrorDescription };

/**
 * The dashboard's countdowns (spec §10.1): the next interview, application deadlines, offer answer deadlines and
 * the user's own countdowns, soonest first, with the days left on the viewer's calendar. Custom countdowns are
 * added here and deleted with confirmation. Self-contained, so any page can mount it.
 */
export function CountdownsWidget() {
  const [viewerZone] = useState(viewerTimeZone);
  const { list, today } = useDashboardCountdowns(viewerZone);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const fail = (error: unknown) => setFeedback({ kind: "failure", failure: describeCountdownError(error) });

  const countdowns = list.data?.countdowns;
  return (
    <section aria-labelledby={HEADING_ID} className={sectionCard}>
      <h2 id={HEADING_ID} ref={heading} tabIndex={-1} className="text-h2">
        {m.countdowns_heading()}
      </h2>
      {list.isError ? (
        <Alert tone="error" title={m.countdowns_failed()}>
          <Button
            variant="secondary"
            className="self-start"
            onPress={() => void list.refetch()}
            isDisabled={list.isFetching}
          >
            {m.error_retry()}
          </Button>
        </Alert>
      ) : countdowns === undefined ? (
        <p>{m.loading()}</p>
      ) : countdowns.length === 0 ? (
        <p className="text-muted">{m.countdowns_empty()}</p>
      ) : (
        <ul aria-labelledby={HEADING_ID} className="flex flex-col gap-3">
          {countdowns.map((countdown) => (
            <CountdownRow
              key={`${countdown.source}:${countdown.subjectType}:${countdown.subjectId}`}
              countdown={countdown}
              today={today}
              viewerZone={viewerZone}
              onDeleted={(title) => {
                setFeedback({ kind: "deleted", title });
                heading.current?.focus();
              }}
              onFailure={fail}
            />
          ))}
        </ul>
      )}
      <div role="status">
        {feedback?.kind === "added" ? m.countdown_added_message({ title: feedback.title }) : null}
        {feedback?.kind === "deleted" ? m.countdown_deleted_message({ title: feedback.title }) : null}
      </div>
      {feedback?.kind === "failure" ? <FailureMessage failure={feedback.failure} /> : null}
      <AddCountdown onAdded={(title) => setFeedback({ kind: "added", title })} />
    </section>
  );
}

interface CountdownRowProps {
  countdown: DashboardCountdownResponse;
  today: string;
  viewerZone: string;
  onDeleted: (title: string) => void;
  onFailure: (error: unknown) => void;
}

/** One countdown: the days left, what it counts down to, when it ends, and delete for the user's own. */
function CountdownRow({ countdown, today, viewerZone, onDeleted, onFailure }: CountdownRowProps) {
  const day = targetDay(countdown, viewerZone);
  const days = day === undefined ? undefined : daysBetween(today, day);
  const soon = days !== undefined && days >= 0 && days <= 1;
  const applicationId = countdown.subjectType === "application" ? countdown.subjectId : undefined;

  return (
    <li className="flex flex-col gap-3 rounded border border-line p-4 sm:flex-row sm:items-center">
      <span
        className={`shrink-0 sm:w-36 font-data font-semibold text-h3 ${
          days !== undefined && days < 0 ? "text-muted" : soon ? "text-warn" : ""
        }`}
      >
        {days === undefined ? null : describeRemaining(days)}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="font-data text-eyebrow text-muted uppercase">
          {sourceLabels[countdown.source]()}
        </span>
        {applicationId ? (
          <TextLink to="/applications/$applicationId" params={{ applicationId }} className="font-semibold">
            {countdown.title}
          </TextLink>
        ) : (
          <span className="font-semibold">{countdown.title}</span>
        )}
        <span className="text-muted">{describeTarget(countdown, viewerZone)}</span>
      </div>
      {countdown.source === "CUSTOM" ? (
        <DeleteCountdown countdown={countdown} onDeleted={onDeleted} onFailure={onFailure} />
      ) : null}
    </li>
  );
}

function DeleteCountdown({
  countdown,
  onDeleted,
  onFailure,
}: Pick<CountdownRowProps, "countdown" | "onDeleted" | "onFailure">) {
  const { mutation, dialog } = useDeleteCountdown({
    onDeleted: (deleted) => onDeleted(deleted.title),
    onFailure,
  });
  const start = () => mutation.mutate(countdown);
  return (
    <>
      <Button
        variant="secondary"
        className="self-start sm:self-center"
        onPress={start}
        isDisabled={mutation.isPending}
        aria-label={m.countdown_delete_named({ title: countdown.title })}
      >
        <DeleteIcon className="size-4" aria-hidden="true" />
        {m.countdown_delete()}
      </Button>
      {dialog}
    </>
  );
}

const FIELD_NAMES = { title: "title", targetDate: "targetDate" } as const;

/** A title and a date; the days left are counted on the viewer's calendar. */
function AddCountdown({ onAdded }: { onAdded: (title: string) => void }) {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [title, setTitle] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const add = useAddCountdown();

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (add.isPending) return;
    setFailure(null);
    add.mutate(
      { title: title.trim(), targetDate },
      {
        onSuccess: (created) => {
          setTitle("");
          setTargetDate("");
          onAdded(created.title);
        },
        onError: (error) => {
          const fields = taskFieldErrorsOf(error);
          if (fields) fieldErrors.set(fields);
          else setFailure(describeCountdownError(error));
        },
      },
    );
  };

  const required = (value: string) => (value.trim() === "" ? m.company_violation_required() : null);
  return (
    <Form
      onSubmit={submit}
      validationErrors={fieldErrors.errors}
      aria-labelledby="countdown-add-heading"
      className="flex flex-col gap-4 border-line border-t pt-4"
    >
      <h3 id="countdown-add-heading" className="text-h3">
        {m.countdown_add_heading()}
      </h3>
      <FailureMessage failure={failure} />
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <TextField
          name={FIELD_NAMES.title}
          label={m.countdown_field_title()}
          value={title}
          onChange={fieldErrors.clearing(FIELD_NAMES.title, setTitle)}
          maxLength={MAX_TITLE_LENGTH}
          validate={required}
          className="flex-1"
        />
        <TextField
          name={FIELD_NAMES.targetDate}
          type="date"
          label={m.countdown_field_date()}
          value={targetDate}
          onChange={fieldErrors.clearing(FIELD_NAMES.targetDate, setTargetDate)}
          validate={required}
        />
      </div>
      <Button type="submit" className="self-start" isDisabled={add.isPending}>
        <AddIcon className="size-4" aria-hidden="true" />
        {m.countdown_add()}
      </Button>
    </Form>
  );
}
