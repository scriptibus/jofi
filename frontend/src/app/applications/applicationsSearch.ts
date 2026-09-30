// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  ApplicationResponseStatus,
  ApplicationSourceResponseKind,
  type SearchApplicationsParams,
  SearchApplicationsSort,
} from "../../api/generated/jofi";

export type Status = ApplicationResponseStatus;
export type SourceKind = ApplicationSourceResponseKind;
export type SortKey = SearchApplicationsSort;
export type Direction = "ASCENDING" | "DESCENDING";
export interface Order {
  sort: SortKey;
  dir: Direction;
}

/** The pipeline order (backend `ApplicationStatus`), which is also how the server sorts by status. */
export const STATUSES = Object.values(ApplicationResponseStatus);
export const SOURCE_KINDS = Object.values(ApplicationSourceResponseKind);
export const SORT_KEYS = Object.values(SearchApplicationsSort);
/** "Updated within the last n days". */
export const UPDATED_WITHIN = [7, 30, 90] as const;
export type UpdatedWithin = (typeof UPDATED_WITHIN)[number];
export const PAGE_SIZE = 50;
/** The board asks for this many at once (the server's largest page, backend `ApplicationSearch.MAX_SIZE`). */
export const BOARD_SIZE = 200;
export type View = "table" | "board";
const MAX_SCORE = 5;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LANGUAGE = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$/;

/**
 * The list's filters, order and page in the URL, so back, reload and a bookmark keep them, and the
 * Kanban board (#36) can read the same filters. `q` is a search over job titles, which are about the
 * posting, not about a person, so it may live in the URL like the companies search (unlike a contact's
 * name). Only ids, enums, numbers and language tags otherwise.
 */
export interface ApplicationsSearch {
  q?: string;
  status?: Status[];
  company?: string;
  source?: SourceKind[];
  language?: string;
  unread?: true;
  updated?: UpdatedWithin;
  wantMin?: number;
  fitMin?: number;
  sort?: SortKey;
  dir?: Direction;
  page?: number;
  /** The Kanban board instead of the table; absent means the table. */
  view?: "board";
}

/** The server's default direction per column (backend `ApplicationSortKey`): dates newest first. */
export function defaultDirection(sort: SortKey): Direction {
  return sort === "UPDATED" || sort === "CREATED" ? "DESCENDING" : "ASCENDING";
}

const oneOf = <T extends string>(values: readonly T[], value: unknown): value is T =>
  values.includes(value as T);

function enumList<T extends string>(values: readonly T[], value: unknown): T[] | undefined {
  const list = (Array.isArray(value) ? value : [value]).filter((item): item is T => oneOf(values, item));
  const unique = values.filter((item) => list.includes(item));
  return unique.length > 0 ? unique : undefined;
}

function score(value: unknown): number | undefined {
  return typeof value === "number" && value > 0 && value <= MAX_SCORE
    ? Math.round(value * 10) / 10
    : undefined;
}

/** Keeps only valid values: the query string is user-editable. */
export function parseApplicationsSearch(search: Record<string, unknown>): ApplicationsSearch {
  const result: ApplicationsSearch = {};
  if (typeof search.q === "string" && search.q.trim() !== "") result.q = search.q.trim();
  const status = enumList(STATUSES, search.status);
  if (status) result.status = status;
  if (typeof search.company === "string" && UUID.test(search.company)) result.company = search.company;
  const source = enumList(SOURCE_KINDS, search.source);
  if (source) result.source = source;
  if (typeof search.language === "string" && LANGUAGE.test(search.language))
    result.language = search.language.toLowerCase();
  if (search.unread === true) result.unread = true;
  if (oneOf(UPDATED_WITHIN.map(String), String(search.updated)))
    result.updated = Number(search.updated) as UpdatedWithin;
  const wantMin = score(search.wantMin);
  if (wantMin !== undefined) result.wantMin = wantMin;
  const fitMin = score(search.fitMin);
  if (fitMin !== undefined) result.fitMin = fitMin;
  if (oneOf(SORT_KEYS, search.sort)) {
    result.sort = search.sort;
    if (search.dir === "ASCENDING" || search.dir === "DESCENDING") result.dir = search.dir;
  }
  if (typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0)
    result.page = search.page;
  if (search.view === "board") result.view = "board";
  return result;
}

/** `?company=` for a new application: the company to preselect (from a company's page or a filtered list). */
export function parseNewApplicationSearch(search: Record<string, unknown>): { company?: string } {
  return typeof search.company === "string" && UUID.test(search.company) ? { company: search.company } : {};
}

/** Midnight (local time) `days` days before `now`: stable all day, so the query key does not churn. */
export function daysAgo(days: number, now: Date): string {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days);
  return start.toISOString();
}

/** The request for these filters: every filter set in the URL, the order and the page. */
export function toSearchParams(search: ApplicationsSearch, now: Date = new Date()): SearchApplicationsParams {
  const params: SearchApplicationsParams = { page: search.page ?? 0, size: PAGE_SIZE };
  if (search.q) params.search = search.q;
  if (search.status) params.status = search.status;
  if (search.company) params.companyId = search.company;
  if (search.source) params.sourceKind = search.source;
  if (search.language) params.language = [search.language];
  if (search.unread) params.unread = true;
  if (search.updated) params.updatedFrom = daysAgo(search.updated, now);
  if (search.wantMin !== undefined) params.wantMin = search.wantMin;
  if (search.fitMin !== undefined) params.fitMin = search.fitMin;
  if (search.sort) {
    params.sort = search.sort;
    params.direction = search.dir ?? defaultDirection(search.sort);
  }
  return params;
}

/**
 * The order the rows are in, for `aria-sort`: the chosen column, else newest update first, except
 * while searching without a chosen order, when the best title match comes first (no column).
 */
export function currentOrder(search: ApplicationsSearch): Order | null {
  if (search.sort) return { sort: search.sort, dir: search.dir ?? defaultDirection(search.sort) };
  return search.q ? null : { sort: "UPDATED", dir: "DESCENDING" };
}

/** A chosen order (null: the server's), back on page one; a default direction stays out of the URL. */
export function withOrder(search: ApplicationsSearch, order: Order | null): ApplicationsSearch {
  const { sort: _sort, dir: _dir, page: _page, ...rest } = search;
  if (!order) return rest;
  const { sort, dir } = order;
  return dir === defaultDirection(sort) ? { ...rest, sort } : { ...rest, sort, dir };
}

/** A press on a column heading: the same column flips its direction, another starts in its default. */
export function sortedBy(search: ApplicationsSearch, sort: SortKey): ApplicationsSearch {
  const current = currentOrder(search);
  const flipped: Direction = current?.dir === "ASCENDING" ? "DESCENDING" : "ASCENDING";
  return withOrder(search, { sort, dir: current?.sort === sort ? flipped : defaultDirection(sort) });
}

export type FilterKey = Exclude<keyof ApplicationsSearch, "sort" | "dir" | "page" | "view">;

/** Sets (or, with `undefined`, clears) one filter; a new filter starts on the first page. */
export function withFilter<K extends FilterKey>(
  search: ApplicationsSearch,
  key: K,
  value: ApplicationsSearch[K] | undefined,
): ApplicationsSearch {
  const { [key]: _old, page: _page, ...rest } = search;
  const empty = value === undefined || (Array.isArray(value) && value.length === 0);
  return empty ? rest : { ...rest, [key]: value };
}

/** Every filter off; the order and the view stay. */
export function withoutFilters({ sort, dir, view }: ApplicationsSearch): ApplicationsSearch {
  return { ...(sort ? { sort } : {}), ...(dir ? { dir } : {}), ...(view ? { view } : {}) };
}

const NOT_FILTERS: readonly string[] = ["sort", "dir", "page", "view"];

export function isFiltered(search: ApplicationsSearch): boolean {
  return Object.keys(search).some((key) => !NOT_FILTERS.includes(key));
}

export function currentView(search: ApplicationsSearch): View {
  return search.view ?? "table";
}

/** The table or the board with the same filters and order; the board has no pages. */
export function withView(search: ApplicationsSearch, view: View): ApplicationsSearch {
  const { view: _old, page: _page, ...rest } = search;
  return view === "board" ? { ...rest, view } : rest;
}

/** The board's request: the same filters and order as the table, the first `BOARD_SIZE` applications. */
export function toBoardSearchParams(
  search: ApplicationsSearch,
  now: Date = new Date(),
): SearchApplicationsParams {
  return { ...toSearchParams({ ...search, page: 0 }, now), size: BOARD_SIZE };
}

export function withPage(search: ApplicationsSearch, page: number): ApplicationsSearch {
  const { page: _old, ...rest } = search;
  return page === 0 ? rest : { ...rest, page };
}
