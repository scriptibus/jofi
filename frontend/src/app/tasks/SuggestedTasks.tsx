// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import {
  acceptTaskSuggestion,
  dismissTaskSuggestion,
  getGetTaskQueryKey,
  getListTaskGroupsQueryKey,
  type TaskResponse,
  type TaskSummaryResponse,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, CheckIcon, CloseIcon } from "../../ui";
import { sectionCard } from "../companies/RelatedRecords";
import { describeTiming, taskTitle } from "./task";
import { TaskLinkChip } from "./taskLinks";
import { type SuggestionPages, suggestionPagesKey, useSuggestionPages } from "./taskPages";

type Decision = "accept" | "dismiss";

/**
 * Accepts or dismisses a suggestion (ADR-0049) based on the version it was listed with. Either way it leaves the
 * suggestions; an accepted one is an open task, so the grouped list loads again. A failure (e.g. decided in another
 * tab meanwhile) reloads the suggestions, so what is shown is what can still be decided.
 */
function useDecideSuggestion() {
  const queryClient = useQueryClient();
  const suggestionsKey = suggestionPagesKey();
  return useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: ({ task, decision }: { task: TaskSummaryResponse; decision: Decision }) =>
      (decision === "accept" ? acceptTaskSuggestion : dismissTaskSuggestion)(task.id, {
        basedOnVersion: task.version,
      }),
    onSuccess: (saved, { decision }) => {
      queryClient.setQueryData<SuggestionPages>(suggestionsKey, (data) =>
        data
          ? {
              ...data,
              pages: data.pages.map((page) => ({
                ...page,
                tasks: page.tasks.filter((task) => task.id !== saved.id),
              })),
            }
          : data,
      );
      queryClient.setQueryData(getGetTaskQueryKey(saved.id), saved);
      if (decision === "accept")
        void queryClient.invalidateQueries({ queryKey: getListTaskGroupsQueryKey() });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: suggestionsKey }),
  });
}

export interface SuggestedTasksProps {
  viewerZone: string;
  /** Told of every accepted or dismissed suggestion, with the server's answer. */
  onDecided: (task: TaskResponse, decision: Decision) => void;
  onFailure: (error: unknown) => void;
}

/**
 * The suggested tasks (spec §10.2), newest first: each with one-click Accept (it becomes an open task) and Dismiss
 * (it never comes back). Focus returns to the heading once a decided suggestion has left the list.
 */
export function SuggestedTasks({ viewerZone, onDecided, onFailure }: SuggestedTasksProps) {
  const suggestions = useSuggestionPages();
  const decide = useDecideSuggestion();
  const heading = useRef<HTMLHeadingElement>(null);

  const onDecide = (task: TaskSummaryResponse, decision: Decision) =>
    decide.mutate(
      { task, decision },
      {
        onSuccess: (saved) => {
          onDecided(saved, decision);
          heading.current?.focus();
          // Entries moved up into the pages already loaded: the next page would skip some.
          if (suggestions.hasNextPage) void suggestions.refetch();
        },
        onError: onFailure,
      },
    );

  const tasks = suggestions.data?.pages.flatMap((page) => page.tasks);
  return (
    <section aria-labelledby="task-suggestions-heading" className={sectionCard}>
      <h2 id="task-suggestions-heading" ref={heading} tabIndex={-1} className="text-h2">
        {m.task_suggestions_heading()}
      </h2>
      {suggestions.isError ? (
        <Alert tone="error" title={m.task_suggestions_failed()}>
          <Button
            variant="secondary"
            className="self-start"
            onPress={() => void suggestions.refetch()}
            isDisabled={suggestions.isFetching}
          >
            {m.error_retry()}
          </Button>
        </Alert>
      ) : tasks === undefined ? (
        <p>{m.loading()}</p>
      ) : tasks.length === 0 ? (
        <p className="text-muted">{m.task_suggestions_empty()}</p>
      ) : (
        <>
          <p className="text-muted">{m.task_suggestions_intro()}</p>
          <ul aria-labelledby="task-suggestions-heading" className="flex flex-col gap-3">
            {tasks.map((task) => (
              <SuggestionRow
                key={task.id}
                task={task}
                viewerZone={viewerZone}
                busy={decide.isPending}
                onDecide={onDecide}
              />
            ))}
          </ul>
          {suggestions.hasNextPage ? (
            <Button
              variant="secondary"
              className="self-start"
              onPress={() => void suggestions.fetchNextPage()}
              isDisabled={suggestions.isFetchingNextPage}
            >
              {m.task_suggestions_show_more()}
            </Button>
          ) : null}
        </>
      )}
    </section>
  );
}

interface SuggestionRowProps {
  task: TaskSummaryResponse;
  viewerZone: string;
  busy: boolean;
  onDecide: (task: TaskSummaryResponse, decision: Decision) => void;
}

/** One suggestion: its title in the user's language, when it would be due, what it is about, and the choice. */
function SuggestionRow({ task, viewerZone, busy, onDecide }: SuggestionRowProps) {
  const title = taskTitle(task);
  return (
    <li className="flex flex-col gap-3 rounded border border-line p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-col gap-2">
        <span className="font-semibold">{title}</span>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-muted">
          <span>{describeTiming(task.timing, viewerZone)}</span>
          {task.link ? <TaskLinkChip link={task.link} /> : null}
        </div>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-3">
        <Button
          onPress={() => onDecide(task, "accept")}
          isDisabled={busy}
          aria-label={m.task_suggestion_accept_named({ title })}
        >
          <CheckIcon className="size-4" aria-hidden="true" />
          {m.task_suggestion_accept()}
        </Button>
        <Button
          variant="secondary"
          onPress={() => onDecide(task, "dismiss")}
          isDisabled={busy}
          aria-label={m.task_suggestion_dismiss_named({ title })}
        >
          <CloseIcon className="size-4" aria-hidden="true" />
          {m.task_suggestion_dismiss()}
        </Button>
      </div>
    </li>
  );
}
