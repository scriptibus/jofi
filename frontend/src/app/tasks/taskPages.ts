// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type InfiniteData, type QueryClient, type QueryKey, useInfiniteQuery } from "@tanstack/react-query";
import {
  getListSuggestedTasksQueryKey,
  getListTaskGroupsQueryKey,
  listSuggestedTasks,
  listTaskGroups,
  type TaskGroupListResponse,
  type TaskGroupResponse,
  type TaskListResponse,
  type TaskSummaryResponse,
} from "../../api/generated/jofi";

/** The server's largest page (backend `PageRequest.MAX_SIZE`, ADR-0056): the lists load this many at a time. */
export const TASK_PAGE_SIZE = 50;

export type TaskGroupPages = InfiniteData<TaskGroupListResponse, number>;
export type SuggestionPages = InfiniteData<TaskListResponse, number>;

/**
 * The key of the paged grouped list. It starts with the generated list key, so invalidating that one (after a task
 * was added or deleted) loads every page held here again.
 */
export const taskGroupPagesKey = (timeZone: string): QueryKey => [
  ...getListTaskGroupsQueryKey({ timeZone }),
  "pages",
];

export const suggestionPagesKey = (): QueryKey => [...getListSuggestedTasksQueryKey(), "pages"];

/** The open tasks by due group, one page after the other; "show more" asks for the next page. */
export function useTaskGroupPages(timeZone: string) {
  return useInfiniteQuery({
    queryKey: taskGroupPagesKey(timeZone),
    queryFn: ({ pageParam }) => listTaskGroups({ timeZone, page: pageParam, size: TASK_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page.hasMore ? last.page.page + 1 : undefined),
  });
}

/** The suggestions waiting for a decision, newest first, one page after the other. */
export function useSuggestionPages() {
  return useInfiniteQuery({
    queryKey: suggestionPagesKey(),
    queryFn: ({ pageParam }) => listSuggestedTasks({ page: pageParam, size: TASK_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page.hasMore ? last.page.page + 1 : undefined),
    meta: { errorHandledLocally: true },
  });
}

/**
 * Every group once, with the tasks of all loaded pages in order: a group continues on the next page. A task is listed
 * once, in the group it was first loaded in, wherever it shows up again (a reopened one, or one whose due time passed
 * between two page reads and so moved to another group).
 */
export function mergeGroups(pages: readonly TaskGroupListResponse[]): TaskGroupResponse[] {
  const merged = new Map<string, TaskGroupResponse>();
  const seen = new Set<string>();
  for (const { groups } of pages) {
    for (const group of groups) {
      const added = group.tasks.filter((task) => !seen.has(task.id));
      for (const task of added) seen.add(task.id);
      const known = merged.get(group.group);
      merged.set(group.group, { ...group, tasks: [...(known?.tasks ?? []), ...added] });
    }
  }
  return [...merged.values()];
}

export function listedTasks(data: TaskGroupPages | undefined): TaskSummaryResponse[] {
  return data?.pages.flatMap((page) => page.groups.flatMap((group) => group.tasks)) ?? [];
}

/** Changes the listed entry of task `id`, wherever it is on the loaded pages; it keeps its place. */
export function updateListed(
  queryClient: QueryClient,
  key: QueryKey,
  id: string,
  change: (listed: TaskSummaryResponse) => TaskSummaryResponse,
) {
  queryClient.setQueryData<TaskGroupPages>(key, (data) =>
    data
      ? {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            groups: page.groups.map((group) => ({
              ...group,
              tasks: group.tasks.map((listed) => (listed.id === id ? change(listed) : listed)),
            })),
          })),
        }
      : data,
  );
}
