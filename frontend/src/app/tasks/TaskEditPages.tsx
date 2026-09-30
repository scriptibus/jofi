// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type QueryClient, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import {
  getGetTaskQueryKey,
  getListTaskGroupsQueryKey,
  type TaskResponse,
  useCreateTask,
  useGetTask,
  useUpdateTask,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, EmptyState, RefreshIcon, TextLink } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { TaskForm } from "./TaskForm";
import { describeTiming, formValues, type TaskFormValues, taskFieldErrorsOf, toTaskRequest } from "./task";
import { describeTaskError, isTaskNotFound, isTaskVersionConflict } from "./taskProblems";

const editRoute = getRouteApi("/_app/tasks/$taskId/edit");

/** After a save: the cache holds the answer, and the grouped list asks again. */
export function storeSavedTask(queryClient: QueryClient, task: TaskResponse) {
  queryClient.setQueryData(getGetTaskQueryKey(task.id), task);
  void queryClient.invalidateQueries({ queryKey: getListTaskGroupsQueryKey() });
}

/** Field errors next to the fields, anything else above the form. */
export function useTaskFormErrors() {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const onError = (error: unknown) => {
    const fields = taskFieldErrorsOf(error);
    if (fields) fieldErrors.set(fields);
    else setFailure(describeTaskError(error));
  };
  const reset = () => {
    setFailure(null);
    fieldErrors.set({});
  };
  return { fieldErrors, failure, onError, reset };
}

/** A new task with every detail (the list's quick add only takes a title and a bucket). */
export function NewTaskPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useTaskFormErrors();
  const create = useCreateTask({ mutation: { meta: { errorHandledLocally: true } } });
  const toList = () => void navigate({ to: "/tasks" });

  const submit = (values: TaskFormValues) => {
    errors.reset();
    create.mutate(
      { data: toTaskRequest(values) },
      {
        onSuccess: (task) => {
          storeSavedTask(queryClient, task);
          toList();
        },
        onError: errors.onError,
      },
    );
  };

  return (
    <>
      <PageHeader title={m.task_new_heading()} />
      <TaskForm
        initial={formValues()}
        fieldErrors={errors.fieldErrors}
        feedback={<FailureMessage failure={errors.failure} />}
        submitLabel={m.task_create_submit()}
        isPending={create.isPending}
        onSubmit={submit}
        onCancel={toList}
      />
    </>
  );
}

export function EditTaskPage() {
  const { taskId } = editRoute.useParams();
  const task = useGetTask(taskId, { query: { meta: { errorHandledLocally: true } } });
  // The form edits the version it was opened with: a background refetch must not replace the user's
  // input, and that version goes back as `basedOnVersion`, so a change made elsewhere meanwhile is a 409.
  const [opened, setOpened] = useState<TaskResponse | undefined>(undefined);
  if (opened === undefined && task.data) setOpened(task.data);

  if (opened === undefined) {
    if (!task.isError) return <p role="status">{m.loading()}</p>;
    return <TaskLoadFailure error={task.error} onRetry={() => void task.refetch()} />;
  }
  const reload = async () => {
    const latest = await task.refetch();
    if (latest.data) setOpened(latest.data);
  };
  return <EditTaskForm key={opened.version} task={opened} onReload={reload} />;
}

function TaskLoadFailure({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isTaskNotFound(error)) {
    return (
      <>
        <PageHeader title={m.task_not_found_heading()} />
        <EmptyState title={m.task_not_found_heading()}>
          {m.task_error_not_found()} <TextLink to="/tasks">{m.task_back_to_list()}</TextLink>
        </EmptyState>
      </>
    );
  }
  return (
    <>
      <PageHeader title={m.error_heading()} />
      <FailureMessage failure={describeTaskError(error)} />
      <Button className="self-start" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </>
  );
}

function EditTaskForm({ task, onReload }: { task: TaskResponse; onReload: () => Promise<void> }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useTaskFormErrors();
  const [conflict, setConflict] = useState(false);
  const update = useUpdateTask({ mutation: { meta: { errorHandledLocally: true } } });
  const toList = () => void navigate({ to: "/tasks" });
  const [initial] = useState(() => formValues(task));

  const submit = (values: TaskFormValues) => {
    errors.reset();
    setConflict(false);
    update.mutate(
      { id: task.id, data: { details: toTaskRequest(values), basedOnVersion: task.version } },
      {
        onSuccess: (saved) => {
          storeSavedTask(queryClient, saved);
          toList();
        },
        onError: (error) => (isTaskVersionConflict(error) ? setConflict(true) : errors.onError(error)),
      },
    );
  };

  const feedback = conflict ? (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{m.task_conflict_edit()}</p>
      <Button variant="secondary" className="self-start" onPress={() => void onReload()}>
        <RefreshIcon className="size-4" aria-hidden="true" />
        {m.company_conflict_reload()}
      </Button>
    </Alert>
  ) : (
    <FailureMessage failure={errors.failure} />
  );

  return (
    <>
      <PageHeader title={m.task_edit_heading()} />
      {initial.timing === "" ? (
        <p className="max-w-2xl text-muted">
          {m.task_edit_timing_passed({ timing: describeTiming(task.timing) })}
        </p>
      ) : null}
      <TaskForm
        initial={initial}
        fieldErrors={errors.fieldErrors}
        feedback={feedback}
        submitLabel={m.company_save_submit()}
        isPending={update.isPending}
        onSubmit={submit}
        onCancel={toList}
      />
    </>
  );
}
