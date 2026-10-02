// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type QueryKey, useIsMutating, useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useEffect, useState } from "react";
import {
  type TaskGroupResponse,
  type TaskResponse,
  type TaskSummaryResponse,
  useCreateTask,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import {
  AddIcon,
  Button,
  EmptyState,
  Form,
  OverdueIcon,
  SegmentedControl,
  TextField,
  TextLink,
  UndoIcon,
} from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { sectionCard } from "../companies/RelatedRecords";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { SuggestedTasks } from "./SuggestedTasks";
import { storeSavedTask } from "./TaskEditPages";
import { TaskRow, useSetTaskDone } from "./TaskRow";
import {
  BUCKETS,
  bucketLabels,
  DEFAULT_BUCKET,
  FIELD_NAMES,
  groupLabels,
  MAX_TITLE_LENGTH,
  type TaskBucket,
  taskFieldErrorsOf,
  taskTitle,
  viewerTimeZone,
} from "./task";
import {
  listedTasks,
  mergeGroups,
  type TaskGroupPages,
  taskGroupPagesKey,
  useTaskGroupPages,
} from "./taskPages";
import { describeTaskError } from "./taskProblems";

/**
 * What the page last has to say: a task added, done or deleted (with undo for done), a suggestion accepted or
 * dismissed, or a failure.
 */
type Feedback =
  | { kind: "added" | "deleted" | "reopened" | "accepted" | "dismissed"; title: string }
  | { kind: "done"; task: TaskResponse }
  | { kind: "failure"; failure: ErrorDescription };

/**
 * Tasks (spec §10.2): a quick add, the suggested tasks to accept or dismiss, then the open tasks grouped by when
 * they are due on the viewer's calendar (the browser's zone goes to the server). Complete with a checkbox (undo
 * reopens), edit, delete with confirmation.
 */
export function TasksPage() {
  const [viewerZone] = useState(viewerTimeZone);
  const listKey = taskGroupPagesKey(viewerZone);
  const list = useTaskGroupPages(viewerZone);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  // A complete or reopen changes which tasks are open: the next page's offset no longer fits the loaded ones.
  const queryClient = useQueryClient();
  const [changed, setChanged] = useState(false);
  const mutating = useIsMutating();
  useEffect(
    () =>
      queryClient.getMutationCache().subscribe((event) => {
        if (event.type === "updated" && (event.action.type === "success" || event.action.type === "error"))
          setChanged(true);
      }),
    [queryClient],
  );
  const showMore = async () => {
    const loaded = changed ? (await list.refetch()).data : list.data;
    setChanged(false);
    if (loaded?.pages.at(-1)?.page.hasMore) await list.fetchNextPage();
  };
  const fail = (error: unknown) => setFeedback({ kind: "failure", failure: describeTaskError(error) });

  return (
    <>
      <PageHeader title={m.nav_tasks()} />
      <QuickAdd
        viewerZone={viewerZone}
        onAdded={(task) => setFeedback({ kind: "added", title: task.title })}
      />
      <FeedbackMessage feedback={feedback} listKey={listKey} onChange={setFeedback} onFailure={fail} />
      <SuggestedTasks
        viewerZone={viewerZone}
        onDecided={(task, decision) =>
          setFeedback({ kind: decision === "accept" ? "accepted" : "dismissed", title: taskTitle(task) })
        }
        onFailure={fail}
      />
      {list.data ? (
        <TaskGroups
          pages={list}
          onShowMore={() => void showMore()}
          busy={mutating > 0 || list.isFetching}
          listKey={listKey}
          viewerZone={viewerZone}
          onDone={(task) => setFeedback(task.status === "DONE" ? { kind: "done", task } : null)}
          onDeleted={(task) => setFeedback({ kind: "deleted", title: taskTitle(task) })}
          onFailure={fail}
        />
      ) : list.isPending ? (
        <p role="status">{m.loading()}</p>
      ) : null}
    </>
  );
}

interface FeedbackProps {
  feedback: Feedback | null;
  listKey: QueryKey;
  onChange: (feedback: Feedback | null) => void;
  onFailure: (error: unknown) => void;
}

/** One polite live region for what just happened; a completed task can be reopened from here. */
function FeedbackMessage({ feedback, listKey, onChange, onFailure }: FeedbackProps) {
  const queryClient = useQueryClient();
  const reopen = useSetTaskDone(listKey);
  if (feedback?.kind === "failure") return <FailureMessage failure={feedback.failure} />;

  const undo = (task: TaskResponse) => {
    // The latest version (the server's answer to the complete), as the checkbox would use it.
    const cached = listedTasks(queryClient.getQueryData<TaskGroupPages>(listKey)).find(
      (other) => other.id === task.id,
    );
    reopen.mutate(
      { task: cached ?? task, done: false },
      { onSuccess: () => onChange({ kind: "reopened", title: taskTitle(task) }), onError: onFailure },
    );
  };

  return (
    <div role="status" className="flex min-h-10 flex-wrap items-center gap-3">
      {feedback?.kind === "done" ? (
        <>
          <span>{m.task_done_message({ title: taskTitle(feedback.task) })}</span>
          <Button variant="secondary" onPress={() => undo(feedback.task)} isDisabled={reopen.isPending}>
            <UndoIcon className="size-4" aria-hidden="true" />
            {m.task_undo()}
          </Button>
        </>
      ) : null}
      {feedback?.kind === "added" ? <span>{m.task_added_message({ title: feedback.title })}</span> : null}
      {feedback?.kind === "deleted" ? <span>{m.task_deleted_message({ title: feedback.title })}</span> : null}
      {feedback?.kind === "reopened" ? (
        <span>{m.task_reopened_message({ title: feedback.title })}</span>
      ) : null}
      {feedback?.kind === "accepted" ? (
        <span>{m.task_suggestion_accepted_message({ title: feedback.title })}</span>
      ) : null}
      {feedback?.kind === "dismissed" ? (
        <span>{m.task_suggestion_dismissed_message({ title: feedback.title })}</span>
      ) : null}
    </div>
  );
}

/** Title and bucket only (default "this week"); everything else on the full form. */
function QuickAdd({ viewerZone, onAdded }: { viewerZone: string; onAdded: (task: TaskResponse) => void }) {
  const queryClient = useQueryClient();
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [title, setTitle] = useState("");
  const [bucket, setBucket] = useState<TaskBucket>(DEFAULT_BUCKET);
  const create = useCreateTask({ mutation: { meta: { errorHandledLocally: true } } });

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    create.mutate(
      { data: { title: title.trim(), timing: { timeZone: viewerZone, bucket }, link: null, notes: null } },
      {
        onSuccess: (task) => {
          storeSavedTask(queryClient, task);
          setTitle("");
          setBucket(DEFAULT_BUCKET);
          onAdded(task);
        },
        onError: (error) => {
          const fields = taskFieldErrorsOf(error);
          if (fields) fieldErrors.set(fields);
          else setFailure(describeTaskError(error));
        },
      },
    );
  };

  return (
    <section aria-labelledby="task-quick-add-heading" className={sectionCard}>
      <h2 id="task-quick-add-heading" className="text-h2">
        {m.task_quick_add_heading()}
      </h2>
      <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-4">
        <FailureMessage failure={failure} />
        <TextField
          name={FIELD_NAMES.title}
          label={m.task_field_title()}
          value={title}
          onChange={fieldErrors.clearing(FIELD_NAMES.title, setTitle)}
          maxLength={MAX_TITLE_LENGTH}
          validate={(value) => (value.trim() === "" ? m.company_violation_required() : null)}
          className="max-w-xl"
        />
        <SegmentedControl<TaskBucket>
          label={m.task_field_when()}
          value={bucket}
          onChange={setBucket}
          options={BUCKETS.map((value) => ({ value, label: bucketLabels[value]() }))}
        />
        <div className="flex flex-wrap items-center gap-4">
          <Button type="submit" isDisabled={create.isPending}>
            <AddIcon className="size-4" aria-hidden="true" />
            {m.task_add()}
          </Button>
          <TextLink to="/tasks/new">{m.task_more_details()}</TextLink>
        </div>
      </Form>
    </section>
  );
}

interface TaskGroupsProps {
  pages: ReturnType<typeof useTaskGroupPages>;
  onShowMore: () => void;
  /** A change or a load is under way: the next page's offset is not known to fit yet. */
  busy: boolean;
  listKey: QueryKey;
  viewerZone: string;
  onDone: (task: TaskResponse) => void;
  onDeleted: (task: TaskSummaryResponse) => void;
  onFailure: (error: unknown) => void;
}

/**
 * Each group with tasks as a section with its count; empty groups are left out. The tasks come a page at a time and
 * run through the groups in order, so only the last group shown can have more tasks on the next page: its count says
 * "50+" until they are loaded.
 */
function TaskGroups({ pages, onShowMore, busy, ...rowProps }: TaskGroupsProps) {
  const groups = mergeGroups(pages.data?.pages ?? []);
  const shown = groups.filter((group) => group.tasks.length > 0);
  if (shown.length === 0) return <EmptyState title={m.tasks_empty_heading()}>{m.tasks_empty()}</EmptyState>;
  const total = pages.data?.pages[0]?.page.total ?? 0;
  const loaded = shown.reduce((sum, group) => sum + group.tasks.length, 0);
  return (
    <div className="flex flex-col gap-8">
      {shown.map((group, index) => (
        <TaskGroupSection
          key={group.group}
          group={group}
          partial={pages.hasNextPage && index === shown.length - 1}
          {...rowProps}
        />
      ))}
      {pages.hasNextPage ? (
        <div className="flex flex-wrap items-center gap-4">
          <Button variant="secondary" onPress={onShowMore} isDisabled={busy}>
            {m.tasks_show_more()}
          </Button>
          <span className="text-muted" role="status">
            {m.tasks_shown({ shown: loaded, total })}
          </span>
        </div>
      ) : null}
    </div>
  );
}

function TaskGroupSection({
  group: { group, tasks },
  partial,
  ...rowProps
}: { group: TaskGroupResponse; partial: boolean } & Omit<TaskGroupsProps, "pages" | "onShowMore" | "busy">) {
  const headingId = `task-group-${group.toLowerCase()}`;
  const open = tasks.filter((task) => task.status !== "DONE").length;
  const overdue = group === "OVERDUE";
  const label = groupLabels[group]();
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className={`flex items-center gap-2 text-h2 ${overdue ? "text-bad" : ""}`}>
        {overdue ? <OverdueIcon className="size-5" aria-hidden="true" /> : null}
        {partial
          ? m.task_group_heading_partial({ group: label, count: open })
          : m.task_group_heading({ group: label, count: open })}
      </h2>
      <ul aria-labelledby={headingId} className="flex flex-col gap-3">
        {tasks.map((task) => (
          <TaskRow key={task.id} task={task} overdue={overdue} {...rowProps} />
        ))}
      </ul>
    </section>
  );
}
