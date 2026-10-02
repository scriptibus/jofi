// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type QueryClient, type QueryKey, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  completeTask,
  deleteTask,
  getGetTaskDashboardQueryKey,
  getGetTaskQueryKey,
  getListDoneTasksQueryKey,
  getListTaskGroupsQueryKey,
  reopenTask,
  type TaskGroupListResponse,
  type TaskResponse,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import {
  Button,
  Checkbox,
  DeleteIcon,
  Disclosure,
  EditIcon,
  Markdown,
  OverdueIcon,
  TextLink,
} from "../../ui";
import { useConfirmation } from "../useConfirmation";
import { DELETE_OPERATION, describeTiming, taskTitle } from "./task";
import { TaskLinkChip } from "./taskLinks";
import { isTaskVersionConflict } from "./taskProblems";

/** Whether the cached grouped list holds the task with `id`. */
function isListed(queryClient: QueryClient, key: QueryKey, id: string): boolean {
  const list = queryClient.getQueryData<TaskGroupListResponse>(key);
  return list?.groups.some((group) => group.tasks.some((task) => task.id === id)) ?? false;
}

/** Replaces `task` wherever it is in the cached grouped list (it keeps its place). */
function patchList(queryClient: QueryClient, key: QueryKey, task: TaskResponse) {
  queryClient.setQueryData<TaskGroupListResponse>(key, (list) =>
    list
      ? {
          groups: list.groups.map((group) => ({
            ...group,
            tasks: group.tasks.map((other) => (other.id === task.id ? task : other)),
          })),
        }
      : list,
  );
}

export interface DoneChange {
  task: TaskResponse;
  done: boolean;
}

/**
 * Completes or reopens a task optimistically (ADR-0041 version check): the checkbox flips at once, the server's
 * answer replaces the task in place, a failure puts the list back. A done task stays in its group, checked, until
 * the list is loaded again (the server lists open tasks only), so it can be reopened right there.
 */
export function useSetTaskDone(listKey: QueryKey) {
  const queryClient = useQueryClient();
  return useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: ({ task, done }: DoneChange) =>
      (done ? completeTask : reopenTask)(task.id, { basedOnVersion: task.version }),
    onMutate: async ({ task, done }: DoneChange) => {
      await queryClient.cancelQueries({ queryKey: listKey });
      const previous = queryClient.getQueryData<TaskGroupListResponse>(listKey);
      patchList(queryClient, listKey, { ...task, status: done ? "DONE" : "OPEN" });
      return { previous };
    },
    onError: (error, _change, context) => {
      if (context?.previous) queryClient.setQueryData(listKey, context.previous);
      // Changed elsewhere meanwhile: load the latest versions, so the next try is based on them.
      if (isTaskVersionConflict(error)) void queryClient.invalidateQueries({ queryKey: listKey });
    },
    onSuccess: (saved) => {
      // A reload meanwhile drops a done task (the server lists open ones only): reopened, it must come back.
      if (!isListed(queryClient, listKey, saved.id))
        void queryClient.invalidateQueries({ queryKey: listKey });
      else patchList(queryClient, listKey, saved);
      queryClient.setQueryData(getGetTaskQueryKey(saved.id), saved);
      // The done view lists what was just completed (or no longer lists what was reopened).
      void queryClient.invalidateQueries({ queryKey: getListDoneTasksQueryKey() });
      void queryClient.invalidateQueries({ queryKey: getGetTaskDashboardQueryKey() });
    },
  });
}

export interface TaskRowProps {
  task: TaskResponse;
  listKey: QueryKey;
  overdue: boolean;
  viewerZone: string;
  /** Told of every successful complete or reopen, e.g. to offer an undo. */
  onDone: (task: TaskResponse) => void;
  onDeleted: (task: TaskResponse) => void;
  onFailure: (error: unknown) => void;
}

/** One task: a checkbox named by its title, when it is due, what it is about, notes, edit and delete. */
export function TaskRow({ task, listKey, overdue, viewerZone, onDone, onDeleted, onFailure }: TaskRowProps) {
  const setDone = useSetTaskDone(listKey);
  const done = task.status === "DONE";
  const title = taskTitle(task);
  const toggle = (next: boolean) =>
    setDone.mutate({ task, done: next }, { onSuccess: onDone, onError: onFailure });

  return (
    <li className="flex flex-col gap-3 rounded border border-line bg-surface p-4 shadow-card sm:flex-row sm:justify-between">
      <div className="flex min-w-0 flex-col gap-2">
        <Checkbox isSelected={done} onChange={toggle} isDisabled={setDone.isPending}>
          <span className={done ? "text-muted line-through" : "font-semibold"}>{title}</span>
        </Checkbox>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 pl-8 text-muted">
          {overdue && !done ? (
            <span className="inline-flex items-center gap-1 font-semibold text-bad">
              <OverdueIcon className="size-4" aria-hidden="true" />
              {m.task_overdue()}
            </span>
          ) : null}
          {done ? <span className="font-semibold">{m.task_done()}</span> : null}
          <span>{describeTiming(task.timing, viewerZone)}</span>
          {task.link ? <TaskLinkChip link={task.link} /> : null}
        </div>
        {task.notes ? (
          <div className="pl-8">
            <Disclosure label={m.task_notes()}>
              <Markdown>{task.notes}</Markdown>
            </Disclosure>
          </div>
        ) : null}
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-3 pl-8 sm:pl-0">
        <TextLink
          to="/tasks/$taskId/edit"
          params={{ taskId: task.id }}
          aria-label={m.task_edit_named({ title })}
          className="inline-flex items-center gap-2"
        >
          <EditIcon className="size-4" aria-hidden="true" />
          {m.company_edit()}
        </TextLink>
        <DeleteTask task={task} onDeleted={onDeleted} onFailure={onFailure} />
      </div>
    </li>
  );
}

/** Delete with the server's two-step confirmation (ADR-0039). */
function DeleteTask({ task, onDeleted, onFailure }: Pick<TaskRowProps, "task" | "onDeleted" | "onFailure">) {
  const queryClient = useQueryClient();
  const { confirmed, dialog } = useConfirmation();
  const remove = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: () =>
      confirmed((options) => deleteTask(task.id, options), {
        expect: { operation: DELETE_OPERATION, targets: [task.id] },
        describe: (effect) => m.task_delete_confirm({ title: effect.name }),
        title: m.task_delete_title(),
        confirmLabel: m.task_delete_action(),
      }),
    // On the mutation, not the call: these still run when the row is gone before the answer is handled.
    onSuccess: (outcome) => {
      if (outcome.status !== "done") return;
      onDeleted(task);
      queryClient.removeQueries({ queryKey: getGetTaskQueryKey(task.id), exact: true });
      void queryClient.invalidateQueries({ queryKey: getListTaskGroupsQueryKey() });
    },
    onError: onFailure,
  });

  const start = () => remove.mutate();

  return (
    <>
      <Button
        variant="secondary"
        onPress={start}
        isDisabled={remove.isPending}
        aria-label={m.task_delete_named({ title: taskTitle(task) })}
      >
        <DeleteIcon className="size-4" aria-hidden="true" />
        {m.task_delete()}
      </Button>
      {dialog}
    </>
  );
}
