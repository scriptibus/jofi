// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for one application's endpoints (#82), as MSW handlers next to
// `fakeCompanyBackend` (which answers the company lookups). It mirrors the backend's status codes and
// problem types: 409 `version-conflict` for a stale `basedOnVersion`, a 428 whose effect counts what goes
// with the application before a delete, and read/unread without a version change.

import { HttpResponse, http } from "msw";
import type {
  ApplicationDetailsRequest,
  ApplicationResponse,
  ApplicationUnreadRequest,
  UpdateApplicationRequest,
} from "../api/generated/jofi";

const origin = () => window.location.origin;
const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
const problem = (status: number, code: string) =>
  json({ type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code }, status);

export const APPLICATION_DELETE_TOKEN = "application-delete-t0k3n";

export function anApplication(
  companyId: string,
  overrides: Partial<ApplicationResponse> = {},
): ApplicationResponse {
  return {
    id: crypto.randomUUID(),
    title: "Backend Engineer",
    companyId,
    location: null,
    remoteShare: null,
    employmentType: null,
    seniority: null,
    deadline: null,
    howApplied: null,
    portalNotes: null,
    payBand: null,
    languageAndTone: {},
    offer: null,
    status: "DISCOVERED",
    declineReason: null,
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

export interface FakeApplicationState {
  applications: ApplicationResponse[];
  /** Every `PUT …/unread` body, in order. */
  unreadCalls: boolean[];
  /** Every accepted `PUT` body, in order. */
  updates: UpdateApplicationRequest[];
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** What goes with each application on delete (the effect's counts), by id. */
  cascade: Record<string, Record<string, number>>;
  /** A rule only the server knows: a request with this pay band maximum is refused with `problem`. */
  refusePayMax?: { value: number; problem: string };
}

function violations(details: ApplicationDetailsRequest, state: FakeApplicationState) {
  const found: { field: string; problem: string }[] = [];
  if (details.title.trim() === "") found.push({ field: "title", problem: "REQUIRED" });
  const max = details.payBand?.max;
  if (state.refusePayMax && max === state.refusePayMax.value)
    found.push({ field: "payBand.max", problem: state.refusePayMax.problem });
  return found;
}

/** What the server stores: the details as sent (it normalises more, which these tests do not need). */
function withDetails(
  application: ApplicationResponse,
  details: ApplicationDetailsRequest,
): ApplicationResponse {
  return {
    ...application,
    title: details.title,
    companyId: details.companyId,
    location: details.location ?? null,
    remoteShare: details.remoteShare ?? null,
    employmentType: details.employmentType ?? null,
    seniority: details.seniority ?? null,
    deadline: details.deadline ?? null,
    howApplied: details.howApplied ?? null,
    portalNotes: details.portalNotes ?? null,
    payBand: details.payBand ?? null,
    languageAndTone: details.languageAndTone ?? {},
    offer: details.offer ?? null,
    version: application.version + 1,
  };
}

export function fakeApplicationBackend(initial: Partial<FakeApplicationState> = {}) {
  const state: FakeApplicationState = {
    applications: [],
    unreadCalls: [],
    updates: [],
    deleteCalls: [],
    cascade: {},
    ...initial,
  };
  const find = (id: unknown) => state.applications.find((application) => application.id === id);
  const store = (saved: ApplicationResponse) => {
    state.applications = state.applications.map((other) => (other.id === saved.id ? saved : other));
    return HttpResponse.json(saved);
  };

  const handlers = [
    http.get(`${origin()}/api/applications/:id`, ({ params }) => {
      const application = find(params.id);
      return application ? HttpResponse.json(application) : problem(404, "application-not-found");
    }),
    http.put(`${origin()}/api/applications/:id`, async ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      const body = (await request.json()) as UpdateApplicationRequest;
      const found = violations(body.details, state);
      if (found.length > 0)
        return json(
          { type: "urn:jofi:problem:applications:invalid-application", status: 400, violations: found },
          400,
        );
      if (body.basedOnVersion !== application.version) return problem(409, "version-conflict");
      state.updates.push(body);
      return store(withDetails(application, body.details));
    }),
    http.put(`${origin()}/api/applications/:id/unread`, async ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      const { unread } = (await request.json()) as ApplicationUnreadRequest;
      state.unreadCalls.push(unread);
      return store({ ...application, unread });
    }),
    http.delete(`${origin()}/api/applications/:id`, ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      if (request.headers.get("Jofi-Confirmation") !== APPLICATION_DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: APPLICATION_DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "applications.delete",
            targets: [application.id],
            effect: {
              kind: "application",
              name: application.title,
              counts: state.cascade[application.id] ?? {},
            },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.applications = state.applications.filter((other) => other.id !== application.id);
      return new HttpResponse(null, { status: 204 });
    }),
  ];

  return { state, handlers };
}
