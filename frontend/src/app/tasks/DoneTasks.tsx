// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  type DoneTaskPageResponse,
  getGetTaskQueryKey,
  getListDoneTasksQueryKey,
  getListTaskGroupsQueryKey,
  reopenTask,
  type TaskResponse,
  useListDoneTasks,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, EmptyState, UndoIcon } from "../../ui";
import { formatInstantDate } from "../applications/format";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { sectionCard } from "../companies/RelatedRecords";
import type { ErrorDescription } from "../problems";
import { taskTitle } from "./task";
import { TaskLinkChip } from "./taskLinks";
import { describeTaskError } from "./taskProblems";

/** Done tasks per page: the server's default and a size one screen holds. */
export const DONE_PAGE_SIZE = 20;

/**
 * Opens a done task again (ADR-0041 version check), based on the version it was listed with. It leaves the done
 * list and joins the open list, so both load again; a failure (reopened or deleted elsewhere meanwhile, e.g. by the
 * AI) reloads the done list too, so what is shown is what can still be reopened.
 */
function useReopenDoneTask() {
  const queryClient = useQueryClient();
  const doneKey = getListDoneTasksQueryKey();
  return useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: (task: TaskResponse) => reopenTask(task.id, { basedOnVersion: task.version }),
    onSuccess: (saved) => {
      queryClient.setQueryData(getGetTaskQueryKey(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: doneKey });
      void queryClient.invalidateQueries({ queryKey: getListTaskGroupsQueryKey() });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: doneKey }),
  });
}

/**
 * The done tasks (#235), the most recently completed first, a page at a time: a completed task is in no other list,
 * so this is the way back, e.g. after the AI completed tasks it should not have. Reopening needs no confirmation (it
 * is an edit). Focus returns to the heading once a reopened task has left the list.
 */
export function DoneTasks() {
  const [page, setPage] = useState(0);
  const [reopened, setReopened] = useState<string | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const done = useListDoneTasks(
    { page, size: DONE_PAGE_SIZE },
    { query: { meta: { errorHandledLocally: true } } },
  );
  const reopen = useReopenDoneTask();

  const total = done.data?.total ?? 0;
  const pages = Math.ceil(total / DONE_PAGE_SIZE);
  // The last task of the last page was reopened: that page is gone, show the one before.
  useEffect(() => {
    if (done.data && page > 0 && page >= pages) setPage(Math.max(0, pages - 1));
  }, [done.data, page, pages]);

  const onReopen = (task: TaskResponse) => {
    setFailure(null);
    setReopened(null);
    reopen.mutate(task, {
      onSuccess: (saved) => {
        setReopened(taskTitle(saved));
        heading.current?.focus();
      },
      onError: (error) => setFailure(describeTaskError(error)),
    });
  };

  return (
    <section aria-labelledby="task-done-heading" className={sectionCard}>
      <h2 id="task-done-heading" ref={heading} tabIndex={-1} className="text-h2">
        {m.tasks_done_heading()}
      </h2>
      <div role="status" className="flex min-h-10 items-center">
        {reopened ? <span>{m.task_reopened_message({ title: reopened })}</span> : null}
      </div>
      <FailureMessage failure={failure} />
      <DoneList
        data={done.data}
        failed={done.isError}
        retrying={done.isFetching}
        onRetry={() => void done.refetch()}
        page={page}
        pages={pages}
        busy={reopen.isPending}
        onPage={setPage}
        onReopen={onReopen}
      />
    </section>
  );
}

interface DoneListProps {
  data: DoneTaskPageResponse | undefined;
  failed: boolean;
  retrying: boolean;
  onRetry: () => void;
  page: number;
  pages: number;
  busy: boolean;
  onPage: (page: number) => void;
  onReopen: (task: TaskResponse) => void;
}

/** What the page shows for the query: a retry on failure, the loading and empty states, or the page with its navigation. */
function DoneList({ data, failed, retrying, onRetry, page, pages, busy, onPage, onReopen }: DoneListProps) {
  if (failed)
    return (
      <Alert tone="error" title={m.tasks_done_failed()}>
        <Button variant="secondary" className="self-start" onPress={onRetry} isDisabled={retrying}>
          {m.error_retry()}
        </Button>
      </Alert>
    );
  if (data === undefined) return <p>{m.loading()}</p>;
  if (data.total === 0)
    return <EmptyState title={m.tasks_done_empty_heading()}>{m.tasks_done_empty()}</EmptyState>;
  return (
    <>
      <p className="text-muted">{m.tasks_done_intro()}</p>
      <ul aria-labelledby="task-done-heading" className="flex flex-col gap-3">
        {data.tasks.map((task) => (
          <DoneRow key={task.id} task={task} busy={busy} onReopen={onReopen} />
        ))}
      </ul>
      {pages > 1 ? (
        <nav aria-label={m.companies_pages_label()} className="flex items-center gap-3">
          <Button variant="secondary" isDisabled={page === 0} onPress={() => onPage(page - 1)}>
            {m.companies_page_previous()}
          </Button>
          <span className="font-data text-muted">{m.companies_page_of({ page: page + 1, pages })}</span>
          <Button variant="secondary" isDisabled={page + 1 >= pages} onPress={() => onPage(page + 1)}>
            {m.companies_page_next()}
          </Button>
        </nav>
      ) : null}
    </>
  );
}

interface DoneRowProps {
  task: TaskResponse;
  busy: boolean;
  onReopen: (task: TaskResponse) => void;
}

/** One done task: its title, when it was completed, what it is about, and Reopen. */
function DoneRow({ task, busy, onReopen }: DoneRowProps) {
  const title = taskTitle(task);
  return (
    <li className="flex flex-col gap-3 rounded border border-line p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-col gap-2">
        <span className="font-semibold">{title}</span>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-muted">
          {task.completedAt ? (
            <span>{m.task_completed_on({ date: formatInstantDate(task.completedAt) })}</span>
          ) : null}
          {task.link ? <TaskLinkChip link={task.link} /> : null}
        </div>
      </div>
      <Button
        variant="secondary"
        className="shrink-0"
        onPress={() => onReopen(task)}
        isDisabled={busy}
        aria-label={m.task_reopen_named({ title })}
      >
        <UndoIcon className="size-4" aria-hidden="true" />
        {m.task_reopen()}
      </Button>
    </li>
  );
}
