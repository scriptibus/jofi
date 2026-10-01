// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import {
  type ApplicationResponse,
  deleteInterview,
  getGetContactQueryOptions,
  type InterviewResponse,
  useListInterviews,
  useLogInterview,
  useUpdateInterview,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Alert, Button, DeleteIcon, EditIcon, Markdown, RefreshIcon } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { sectionCard } from "../companies/RelatedRecords";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { isApplicationVersionConflict } from "./applicationProblems";
import { LoadFailure } from "./DescriptionVersions";
import { InterviewForm } from "./InterviewForm";
import {
  DELETE_INTERVIEW_OPERATION,
  describeInterviewDelete,
  describeInterviewError,
  formatAgreedTime,
  interviewFieldErrors,
  interviewFormValues,
  refreshInterviews,
  timeOnUserClock,
} from "./interviews";
import { interviewResultLabels, interviewTypeLabels } from "./labels";

/** What the form is open for: a new interview, or the snapshot of one as it was when Edit was pressed. */
type Editing = { kind: "new" } | { kind: "edit"; interview: InterviewResponse } | null;

const quietly = { meta: { errorHandledLocally: true } } as const;

/**
 * The Interviews tab (spec §6.1): the application's interviews and calls in start order, to log, edit and
 * delete (with the server's confirmation, ADR-0039). Each change also refreshes the timeline.
 */
export function InterviewsTab({ application }: { application: ApplicationResponse }) {
  const queryClient = useQueryClient();
  const interviews = useListInterviews(application.id, { query: quietly });
  const [editing, setEditing] = useState<Editing>(null);
  const [done, setDone] = useState<string | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const { confirmed, dialog } = useConfirmation();
  const remove = useMutation({
    ...quietly,
    mutationFn: (interview: InterviewResponse) =>
      confirmed((options) => deleteInterview(application.id, interview.id, options), {
        expect: { operation: DELETE_INTERVIEW_OPERATION, targets: [interview.id] },
        describe: describeInterviewDelete,
        title: m.application_interview_delete_title(),
        confirmLabel: m.application_interview_delete(),
      }),
  });

  const finished = (message: string) => {
    setEditing(null);
    setDone(message);
    void refreshInterviews(queryClient, application.id);
  };
  const open = (next: Editing) => {
    setDone(null);
    setFailure(null);
    setEditing(next);
  };
  const startDelete = (interview: InterviewResponse) => {
    open(null);
    remove.mutate(interview, {
      onSuccess: (outcome) => {
        if (outcome.status === "done") finished(m.application_interview_deleted());
      },
      onError: (error) => {
        setFailure(describeInterviewError(error));
        void refreshInterviews(queryClient, application.id);
      },
    });
  };
  const reloadForEdit = async (id: string) => {
    const latest = (await interviews.refetch()).data?.interviews.find((interview) => interview.id === id);
    if (latest) open({ kind: "edit", interview: latest });
    else {
      open(null);
      setFailure({ message: m.application_interview_error_not_found() });
    }
  };

  return (
    <section aria-labelledby="application-interviews-heading" className={sectionCard}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="application-interviews-heading" className="text-h2">
          {m.application_interviews_heading()}
        </h2>
        {editing?.kind === "new" ? null : (
          <Button onPress={() => open({ kind: "new" })}>
            <AddIcon className="size-4" aria-hidden="true" />
            {m.application_interview_log()}
          </Button>
        )}
      </div>
      <p role="status" className="empty:hidden">
        {done}
      </p>
      <FailureMessage failure={failure} />
      {editing?.kind === "new" ? (
        <LogInterview application={application} onDone={finished} onCancel={() => open(null)} />
      ) : null}
      {interviews.data === undefined ? (
        interviews.isError ? (
          <LoadFailure
            error={interviews.error}
            message={m.application_interviews_failed()}
            onRetry={() => void interviews.refetch()}
          />
        ) : (
          <p role="status">{m.loading()}</p>
        )
      ) : interviews.data.interviews.length === 0 ? (
        <p className="text-muted">{m.application_interviews_empty()}</p>
      ) : (
        <ol aria-labelledby="application-interviews-heading" className="flex flex-col gap-3">
          {interviews.data.interviews.map((interview) => (
            <li key={interview.id}>
              {editing?.kind === "edit" && editing.interview.id === interview.id ? (
                <EditInterview
                  key={editing.interview.version}
                  application={application}
                  interview={editing.interview}
                  onDone={finished}
                  onCancel={() => open(null)}
                  onReload={() => void reloadForEdit(interview.id)}
                />
              ) : (
                <InterviewCard
                  interview={interview}
                  isBusy={remove.isPending}
                  onEdit={() => open({ kind: "edit", interview })}
                  onDelete={() => startDelete(interview)}
                />
              )}
            </li>
          ))}
        </ol>
      )}
      {dialog}
    </section>
  );
}

/** Field errors next to the fields, anything else above the form. */
function useInterviewFormErrors() {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const onError = (error: unknown) => {
    const fields = interviewFieldErrors(error);
    if (fields) fieldErrors.set(fields);
    else setFailure(describeInterviewError(error));
  };
  const reset = () => {
    setFailure(null);
    fieldErrors.set({});
  };
  return { fieldErrors, failure, onError, reset };
}

interface FormHostProps {
  application: ApplicationResponse;
  onDone: (message: string) => void;
  onCancel: () => void;
}

function LogInterview({ application, onDone, onCancel }: FormHostProps) {
  const errors = useInterviewFormErrors();
  const log = useLogInterview({ mutation: quietly });
  return (
    <InterviewForm
      application={application}
      initial={interviewFormValues()}
      title={m.application_interview_log()}
      fieldErrors={errors.fieldErrors}
      feedback={<FailureMessage failure={errors.failure} />}
      submitLabel={m.application_interview_log_submit()}
      isPending={log.isPending}
      onCancel={onCancel}
      onSubmit={(data) => {
        errors.reset();
        log.mutate(
          { id: application.id, data },
          { onSuccess: () => onDone(m.application_interview_logged()), onError: errors.onError },
        );
      }}
    />
  );
}

/**
 * Edits the snapshot taken when Edit was pressed (ADR-0041): a refetch never replaces the user's input, and its
 * version goes back as `basedOnVersion`, so a change made elsewhere meanwhile is a 409 with a way to reload.
 */
function EditInterview({
  application,
  interview,
  onDone,
  onCancel,
  onReload,
}: FormHostProps & { interview: InterviewResponse; onReload: () => void }) {
  const errors = useInterviewFormErrors();
  const [conflict, setConflict] = useState(false);
  const update = useUpdateInterview({ mutation: quietly });
  const feedback = conflict ? (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{m.application_interview_conflict()}</p>
      <Button variant="secondary" className="self-start" onPress={onReload}>
        <RefreshIcon className="size-4" aria-hidden="true" />
        {m.company_conflict_reload()}
      </Button>
    </Alert>
  ) : (
    <FailureMessage failure={errors.failure} />
  );
  return (
    <InterviewForm
      application={application}
      initial={interviewFormValues(interview)}
      title={m.application_interview_edit_title({ type: interviewTypeLabels[interview.type]() })}
      fieldErrors={errors.fieldErrors}
      feedback={feedback}
      submitLabel={m.application_interview_save()}
      isPending={update.isPending}
      onCancel={onCancel}
      onSubmit={(details) => {
        errors.reset();
        setConflict(false);
        update.mutate(
          {
            id: application.id,
            interviewId: interview.id,
            data: { details, basedOnVersion: interview.version },
          },
          {
            onSuccess: () => onDone(m.application_interview_saved()),
            onError: (error) =>
              isApplicationVersionConflict(error) ? setConflict(true) : errors.onError(error),
          },
        );
      }}
    />
  );
}

interface InterviewCardProps {
  interview: InterviewResponse;
  isBusy: boolean;
  onEdit: () => void;
  onDelete: () => void;
}

function InterviewCard({ interview, isBusy, onEdit, onDelete }: InterviewCardProps) {
  const type = interviewTypeLabels[interview.type]();
  const agreed = formatAgreedTime(interview.localStart, interview.timeZone);
  const yours = timeOnUserClock(interview);
  const named = { type, time: agreed };
  return (
    <article className="flex flex-col gap-3 rounded border border-line bg-bg p-4">
      <div className="flex flex-col gap-1">
        <h3 className="font-display text-h3">{type}</h3>
        <time dateTime={interview.startsAt} className="font-data">
          {agreed}
        </time>
        {yours ? <p className="text-muted">{m.application_interview_your_time({ time: yours })}</p> : null}
      </div>
      <dl className="grid gap-3 sm:grid-cols-2">
        <Fact label={m.application_interview_field_outcome()}>
          {interview.outcome
            ? interviewResultLabels[interview.outcome]()
            : m.application_interview_outcome_open()}
        </Fact>
        <Fact label={m.application_interview_field_participants()}>
          <ParticipantNames ids={interview.participantIds} />
        </Fact>
        {interview.preparationNotes ? (
          <Fact label={m.application_interview_field_preparation()}>
            <Markdown>{interview.preparationNotes}</Markdown>
          </Fact>
        ) : null}
        {interview.notes ? (
          <Fact label={m.application_interview_field_notes()}>
            <Markdown>{interview.notes}</Markdown>
          </Fact>
        ) : null}
      </dl>
      <div className="flex flex-wrap gap-3">
        <Button variant="secondary" aria-label={m.application_interview_edit_named(named)} onPress={onEdit}>
          <EditIcon className="size-4" aria-hidden="true" />
          {m.company_edit()}
        </Button>
        <Button
          variant="secondary"
          aria-label={m.application_interview_delete_named(named)}
          isDisabled={isBusy}
          onPress={onDelete}
        >
          <DeleteIcon className="size-4" aria-hidden="true" />
          {m.company_delete()}
        </Button>
      </div>
    </article>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="font-data text-eyebrow text-muted uppercase">{label}</dt>
      <dd className="break-words">{children}</dd>
    </div>
  );
}

/** The participants' names, in the order they were chosen; a deleted contact is gone from the list already. */
function ParticipantNames({ ids }: { ids: string[] }) {
  const contacts = useQueries({
    queries: ids.map((id) => getGetContactQueryOptions(id, { query: quietly })),
  });
  if (ids.length === 0)
    return <span className="text-muted">{m.application_interview_participants_none()}</span>;
  const names = contacts.map((contact) => {
    if (contact.data) return contact.data.name;
    return contact.isError ? m.application_contacts_unknown() : m.loading();
  });
  return <>{names.join(", ")}</>;
}
