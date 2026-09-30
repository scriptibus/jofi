// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ApplicationPageResponse, ApplicationResponse } from "../../api/generated/jofi";
import type { DragTypesView } from "../../ui";
import type { Status } from "./labels";
import { canMoveTo, PIPELINE, TERMINAL } from "./statusMatrix";

// The Kanban board's logic (spec §6.3, #36): which column a card is in, what a dragged card carries, and
// which columns accept it (ADR-0044 via `statusMatrix`). Kept apart from the components for unit tests.

/** The drag type whose value is the application's id. */
export const APPLICATION_DRAG_TYPE = "application/x-jofi-application";

/**
 * The drag type that says which status the dragged card is in. A drop target sees only the types before
 * the drop, not their values, so the status is part of the type: that is what lets a column refuse a move
 * the matrix does not allow while the card is still in the air.
 */
export function statusDragType(status: Status): string {
  return `application/x-jofi-status-${status.toLowerCase()}`;
}

/** What a dragged card carries: its id, its status (as a type) and its title as plain text. */
export function boardDragData(application: ApplicationResponse): Record<string, string> {
  return {
    [APPLICATION_DRAG_TYPE]: application.id,
    [statusDragType(application.status)]: application.status,
    "text/plain": application.title,
  };
}

const ALL: readonly Status[] = [...PIPELINE, ...TERMINAL];

/** The status of the dragged card, from its types; null for anything that is not one of our cards. */
export function draggedStatus(types: DragTypesView): Status | null {
  if (!types.has(APPLICATION_DRAG_TYPE)) return null;
  return ALL.find((status) => types.has(statusDragType(status))) ?? null;
}

/** Whether a card in `from` may be dropped on the column `to`: another column, and a move the matrix allows. */
export function canDropOn(from: Status, to: Status): boolean {
  return from !== to && canMoveTo(from, to);
}

/** The applications per column, each column in the order the server sent them. */
export function groupByStatus(
  applications: readonly ApplicationResponse[],
): Record<Status, ApplicationResponse[]> {
  const columns = Object.fromEntries(ALL.map((status) => [status, [] as ApplicationResponse[]])) as Record<
    Status,
    ApplicationResponse[]
  >;
  for (const application of applications) columns[application.status].push(application);
  return columns;
}

/** The page with one application in another column, for the optimistic move before the server answers. */
export function withStatus(
  page: ApplicationPageResponse | undefined,
  id: string,
  status: Status,
): ApplicationPageResponse | undefined {
  if (!page) return page;
  return {
    ...page,
    applications: page.applications.map((application) =>
      application.id === id ? { ...application, status } : application,
    ),
  };
}
