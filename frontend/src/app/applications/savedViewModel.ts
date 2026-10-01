// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import type { ApplicationListQuery, SavedViewResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription, isProblem } from "../problems";
import {
  type ApplicationsSearch,
  daysAgo,
  defaultDirection,
  parseApplicationsSearch,
  UPDATED_WITHIN,
  withOrder,
} from "./applicationsSearch";

const APPLICATIONS = "urn:jofi:problem:applications:";

/** The confirmed delete's operation (backend `SavedView.DELETE_OPERATION`, ADR-0050). */
export const DELETE_VIEW_OPERATION = "saved-views.delete";

/** Problem types of the saved view endpoints (backend `ApplicationProblems`). */
export const SavedViewProblemType = {
  notFound: `${APPLICATIONS}saved-view-not-found`,
  versionConflict: `${APPLICATIONS}version-conflict`,
} as const;

export function isViewVersionConflict(error: unknown): boolean {
  return isProblem(error, SavedViewProblemType.versionConflict);
}

export function isViewNotFound(error: unknown): boolean {
  return isProblem(error, SavedViewProblemType.notFound);
}

/** A failed saved view call in the user's language; the rest goes to `describeError`. */
export function describeViewError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.saved_views_error_mismatch() };
  if (isViewVersionConflict(error)) return { message: m.saved_views_conflict() };
  if (isViewNotFound(error)) return { message: m.saved_views_error_not_found() };
  return describeError(error);
}

/** The view's name problems (backend `ApplicationProblem`), for the name field. */
export const VIEW_VIOLATION_MESSAGES: Readonly<Record<string, () => string>> = {
  REQUIRED: m.saved_views_violation_required,
  TOO_LONG: m.saved_views_violation_too_long,
  INVALID_CHARACTER: m.saved_views_violation_invalid_character,
  TAKEN: m.saved_views_violation_taken,
};

export const MAX_VIEW_NAME_LENGTH = 100;

/**
 * The list's filters and order as a view's filter (the list's query parameters, ADR-0050). "Updated in the
 * last n days" is stored as the day it starts from, counted from `now`, the moment the view is stored.
 */
export function toViewFilter(search: ApplicationsSearch, now: Date = new Date()): ApplicationListQuery {
  const filter: ApplicationListQuery = {};
  if (search.q) filter.search = search.q;
  if (search.status) filter.status = search.status;
  if (search.company) filter.companyId = search.company;
  if (search.source) filter.sourceKind = search.source;
  if (search.language) filter.language = [search.language];
  if (search.unread) filter.unread = true;
  if (search.updated) filter.updatedFrom = daysAgo(search.updated, now);
  if (search.wantMin !== undefined) filter.wantMin = search.wantMin;
  if (search.fitMin !== undefined) filter.fitMin = search.fitMin;
  if (search.sort) {
    filter.sort = search.sort;
    filter.direction = search.dir ?? defaultDirection(search.sort);
  }
  return filter;
}

/** Filters a view may hold (another client saved them) that this page has no control for. */
const NOT_SHOWN: readonly (keyof ApplicationListQuery)[] = [
  "contactId",
  "createdFrom",
  "createdTo",
  "updatedTo",
  "wantMax",
  "fitMax",
];

const DAY_MS = 24 * 60 * 60 * 1000;

/** Calendar days between two instants in local time, so a change to or from summer time does not count. */
function calendarDays(from: Date, to: Date): number {
  const day = (date: Date) => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
  return Math.round((day(to) - day(from)) / DAY_MS);
}

/**
 * "Updated in the last n days" back from the stored start day: the days between it and the moment the view
 * was stored (`storedAt`), so reopening a "last 7 days" view next month shows the last 7 days from then.
 */
export function updatedWithin(updatedFrom: string, storedAt: string): number | undefined {
  const days = calendarDays(new Date(updatedFrom), new Date(storedAt));
  return (UPDATED_WITHIN as readonly number[]).includes(days) ? days : undefined;
}

export interface OpenedView {
  /** The list's filters and order of the view, as the URL holds them. */
  search: ApplicationsSearch;
  /** The view holds a filter this page cannot show, which was left out. */
  leftOut: boolean;
}

/** A view's filter as the list's URL state; filters the page has no control for are left out and reported. */
export function openView(view: SavedViewResponse): OpenedView {
  const { filter } = view;
  const days = filter.updatedFrom ? updatedWithin(filter.updatedFrom, view.updatedAt) : undefined;
  const raw: Record<string, unknown> = {
    q: filter.search ?? undefined,
    status: filter.status ?? undefined,
    company: filter.companyId ?? undefined,
    source: filter.sourceKind ?? undefined,
    language: filter.language?.length === 1 ? filter.language[0] : undefined,
    unread: filter.unread ?? undefined,
    updated: days,
    wantMin: filter.wantMin ?? undefined,
    fitMin: filter.fitMin ?? undefined,
  };
  const parsed = parseApplicationsSearch(raw);
  const search = filter.sort
    ? withOrder(parsed, { sort: filter.sort, dir: filter.direction ?? defaultDirection(filter.sort) })
    : parsed;
  const leftOut =
    NOT_SHOWN.some((key) => filter[key] !== undefined && filter[key] !== null) ||
    (filter.language?.length ?? 0) > 1 ||
    (filter.updatedFrom != null && days === undefined) ||
    filter.unread === false;
  return { search, leftOut };
}

/**
 * The filter to send with a rename: the stored one unchanged, except that "updated in the last n days" is
 * counted again from `now`, since a rename moves the moment the view counts from.
 */
export function filterForRename(view: SavedViewResponse, now: Date = new Date()): ApplicationListQuery {
  const days = view.filter.updatedFrom ? updatedWithin(view.filter.updatedFrom, view.updatedAt) : undefined;
  return days === undefined ? view.filter : { ...view.filter, updatedFrom: daysAgo(days, now) };
}
