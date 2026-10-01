// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the application list (`GET /api/applications`, #83) and read/unread, as MSW
// handlers to put before `fakeCompanyBackend` (which answers the company names). It filters by status,
// company, unread, language, source and title (a case-insensitive substring; the real fuzzy search runs
// in the e2e suite), sorts by title, deadline (none last) or update, and pages. It records every request's
// query string, so tests can check what the UI asked for. Status changes (for the board) follow the
// backend: 409 for an old `basedOnVersion` or a move the matrix does not allow.

import { HttpResponse, http } from "msw";
import type {
  ApplicationResponse,
  ApplicationUnreadRequest,
  ChangeApplicationStatusRequest,
} from "../api/generated/jofi";
import { canMoveTo } from "../app/applications/statusMatrix";
import { type FakeSavedViewState, fakeSavedViewBackend } from "./fakeSavedViewBackend";

const origin = () => window.location.origin;

export function aListedApplication(
  companyId: string,
  overrides: Partial<ApplicationResponse> = {},
): ApplicationResponse {
  return {
    id: crypto.randomUUID(),
    title: "Backend Engineer",
    companyId,
    deadline: null,
    languageAndTone: {},
    status: "DISCOVERED",
    contactIds: [],
    unread: false,
    wantScore: null,
    fitScore: null,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    sources: [],
    ...overrides,
  };
}

export interface FakeApplicationListState {
  applications: ApplicationResponse[];
  /** Every list request's query string, in order. */
  searches: URLSearchParams[];
  /** Answer every list request with this status instead (a 4xx: no retries). */
  failWith: number | null;
  /** Every accepted `PUT …/status` body, in order. */
  statusChanges: ChangeApplicationStatusRequest[];
  /** While set, a status change waits for this promise before it answers (to see the optimistic move). */
  statusGate: Promise<void> | null;
}

const problem = (status: number, code: string) =>
  HttpResponse.json(
    { type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );

type Compare = (a: ApplicationResponse, b: ApplicationResponse) => number;

const orders: Record<string, Compare> = {
  TITLE: (a, b) => a.title.localeCompare(b.title),
  UPDATED: (a, b) => a.updatedAt.localeCompare(b.updatedAt),
  DEADLINE: (a, b) => (a.deadline ?? "").localeCompare(b.deadline ?? ""),
};

function matches(application: ApplicationResponse, params: URLSearchParams): boolean {
  const statuses = params.getAll("status");
  const languages = params.getAll("language");
  const kinds = params.getAll("sourceKind");
  const language =
    application.languageAndTone.applicationLanguage ?? application.languageAndTone.postingLanguage;
  return (
    application.title.toLowerCase().includes(params.get("search")?.toLowerCase() ?? "") &&
    (statuses.length === 0 || statuses.includes(application.status)) &&
    (!params.has("companyId") || application.companyId === params.get("companyId")) &&
    (!params.has("unread") || String(application.unread) === params.get("unread")) &&
    (languages.length === 0 || languages.some((tag) => language?.startsWith(tag))) &&
    (kinds.length === 0 || application.sources.some((source) => kinds.includes(source.kind)))
  );
}

function sorted(applications: ApplicationResponse[], params: URLSearchParams): ApplicationResponse[] {
  const sort = params.get("sort") ?? "UPDATED";
  const descending =
    (params.get("direction") ?? (sort === "UPDATED" ? "DESCENDING" : "ASCENDING")) === "DESCENDING";
  const compare = orders[sort] ?? orders.UPDATED;
  const withValue = sort === "DEADLINE" ? applications.filter((a) => a.deadline) : applications;
  const rest = sort === "DEADLINE" ? applications.filter((a) => !a.deadline) : [];
  const ordered = [...withValue].sort((a, b) => (compare?.(a, b) ?? 0) * (descending ? -1 : 1));
  return [...ordered, ...rest];
}

/** The list's backend; its saved views (empty unless `views` says otherwise) come with it, as the page loads both. */
export function fakeApplicationListBackend(
  initial: Partial<FakeApplicationListState> = {},
  views: Partial<FakeSavedViewState> = {},
) {
  const savedViews = fakeSavedViewBackend(views);
  const state: FakeApplicationListState = {
    applications: [],
    searches: [],
    failWith: null,
    statusChanges: [],
    statusGate: null,
    ...initial,
  };

  // Saved views first: `/api/applications/saved-views` would otherwise match `/api/applications/:id` elsewhere.
  const handlers = [
    ...savedViews.handlers,
    http.get(`${origin()}/api/applications`, ({ request }) => {
      const params = new URL(request.url).searchParams;
      state.searches.push(params);
      if (state.failWith !== null)
        return HttpResponse.json(
          { title: "Unavailable", status: state.failWith },
          { status: state.failWith, headers: { "Content-Type": "application/problem+json" } },
        );
      const page = Number(params.get("page") ?? 0);
      const size = Number(params.get("size") ?? 50);
      const matching = sorted(
        state.applications.filter((application) => matches(application, params)),
        params,
      );
      const applications = matching.slice(page * size, (page + 1) * size);
      return HttpResponse.json({ applications, page, size, total: matching.length });
    }),
    http.put(`${origin()}/api/applications/:id/unread`, async ({ request, params }) => {
      const { unread } = (await request.json()) as ApplicationUnreadRequest;
      const application = state.applications.find((candidate) => candidate.id === params.id);
      if (!application) return HttpResponse.json({ status: 404 }, { status: 404 });
      application.unread = unread;
      return HttpResponse.json(application);
    }),
    http.put(`${origin()}/api/applications/:id/status`, async ({ request, params }) => {
      const body = (await request.json()) as ChangeApplicationStatusRequest;
      await state.statusGate;
      const index = state.applications.findIndex((candidate) => candidate.id === params.id);
      const application = state.applications[index];
      if (!application) return problem(404, "application-not-found");
      if (body.basedOnVersion !== application.version) return problem(409, "version-conflict");
      if (!canMoveTo(application.status, body.status)) return problem(409, "invalid-transition");
      state.statusChanges.push(body);
      const declineReason = body.declineCategory
        ? { category: body.declineCategory, text: body.reason ?? null }
        : null;
      const saved = { ...application, status: body.status, declineReason, version: application.version + 1 };
      state.applications[index] = saved;
      return HttpResponse.json(saved);
    }),
  ];

  return { state, handlers, views: savedViews.state };
}
