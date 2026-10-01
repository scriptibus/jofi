// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for one application's endpoints (#82) and create, as MSW handlers next to
// `fakeCompanyBackend` (which answers the company lookups). It mirrors the backend's status codes and
// problem types: 409 `version-conflict` for a stale `basedOnVersion`, a 428 whose effect counts what goes
// with the application before a delete, and read/unread without a version change. Status changes follow
// ADR-0044 (409 `invalid-transition`, a decline category required for Declined and Rejected) and append to
// the status history, which starts with the status the application was created in. Linking contacts
// replaces the whole set (409 on a stale version, 400 `contactIds` for unknown contacts or too many).

import { HttpResponse, http } from "msw";
import type {
  ApplicationContactsRequest,
  ApplicationDetailsRequest,
  ApplicationResponse,
  ApplicationUnreadRequest,
  ChangeApplicationStatusRequest,
  StatusChangeResponse,
  UpdateApplicationRequest,
} from "../api/generated/jofi";
import { MAX_CONTACTS } from "../app/applications/contactLinks";
import { canMoveTo, takesDeclineReason } from "../app/applications/statusMatrix";

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
  /** Every accepted `POST` body, in order. */
  creates: ApplicationDetailsRequest[];
  /** The companies that exist; when set, any other `companyId` is refused (`NOT_FOUND`). */
  companyIds?: string[];
  /** Told of every created application, e.g. to add it to a list backend's state. */
  onCreate?: (application: ApplicationResponse) => void;
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** What goes with each application on delete (the effect's counts), by id. */
  cascade: Record<string, Record<string, number>>;
  /** Every accepted `PUT …/status` body, in order. */
  statusChanges: ChangeApplicationStatusRequest[];
  /** The status history by application id; without an entry, the one initial change. */
  history: Record<string, StatusChangeResponse[]>;
  /** The status history answers 500 (to show its own failure). */
  historyFails?: boolean;
  /** Every accepted `PUT …/contacts` body, in order. */
  contactLinks: ApplicationContactsRequest[];
  /** The contacts that exist; when set, linking any other id is refused (`contactIds` `NOT_FOUND`). */
  knownContactIds?: () => string[];
  /** A rule only the server knows: a request with this pay band maximum is refused with `problem`. */
  refusePayMax?: { value: number; problem: string };
}

function violations(details: ApplicationDetailsRequest, state: FakeApplicationState) {
  const found: { field: string; problem: string }[] = [];
  if (details.title.trim() === "") found.push({ field: "title", problem: "REQUIRED" });
  if (state.companyIds && !state.companyIds.includes(details.companyId))
    found.push({ field: "companyId", problem: "NOT_FOUND" });
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
    creates: [],
    deleteCalls: [],
    cascade: {},
    statusChanges: [],
    history: {},
    contactLinks: [],
    ...initial,
  };
  const find = (id: unknown) => state.applications.find((application) => application.id === id);
  const store = (saved: ApplicationResponse) => {
    state.applications = state.applications.map((other) => (other.id === saved.id ? saved : other));
    return HttpResponse.json(saved);
  };

  const historyOf = (application: ApplicationResponse): StatusChangeResponse[] => {
    state.history[application.id] ??= [
      { from: null, to: application.status, actor: { kind: "USER" }, at: application.createdAt },
    ];
    return state.history[application.id] ?? [];
  };

  const refused = (found: { field: string; problem: string }[]) =>
    json({ type: "urn:jofi:problem:applications:invalid-application", status: 400, violations: found }, 400);

  const handlers = [
    http.get(`${origin()}/api/applications/:id/status-history`, ({ params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      if (state.historyFails) return problem(500, "storage-unavailable");
      return HttpResponse.json({ changes: historyOf(application) });
    }),
    http.put(`${origin()}/api/applications/:id/status`, async ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      const body = (await request.json()) as ChangeApplicationStatusRequest;
      if (takesDeclineReason(body.status) && !body.declineCategory)
        return refused([{ field: "declineCategory", problem: "REQUIRED" }]);
      if (body.basedOnVersion !== application.version) return problem(409, "version-conflict");
      if (!canMoveTo(application.status, body.status)) return problem(409, "invalid-transition");
      state.statusChanges.push(body);
      historyOf(application).push({
        from: application.status,
        to: body.status,
        reason: body.reason ?? null,
        declineCategory: body.declineCategory ?? null,
        actor: { kind: "USER" },
        at: "2026-09-30T11:00:00Z",
      });
      const declineReason = body.declineCategory
        ? { category: body.declineCategory, text: body.reason ?? null }
        : null;
      return store({ ...application, status: body.status, declineReason, version: application.version + 1 });
    }),
    http.post(`${origin()}/api/applications`, async ({ request }) => {
      const body = (await request.json()) as ApplicationDetailsRequest;
      const found = violations(body, state);
      if (found.length > 0) return refused(found);
      state.creates.push(body);
      const created = { ...withDetails(anApplication(body.companyId), body), version: 0 };
      state.applications = [...state.applications, created];
      state.onCreate?.(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${origin()}/api/applications/:id`, ({ params }) => {
      const application = find(params.id);
      return application ? HttpResponse.json(application) : problem(404, "application-not-found");
    }),
    http.put(`${origin()}/api/applications/:id`, async ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      const body = (await request.json()) as UpdateApplicationRequest;
      const found = violations(body.details, state);
      if (found.length > 0) return refused(found);
      if (body.basedOnVersion !== application.version) return problem(409, "version-conflict");
      state.updates.push(body);
      return store(withDetails(application, body.details));
    }),
    http.put(`${origin()}/api/applications/:id/contacts`, async ({ request, params }) => {
      const application = find(params.id);
      if (!application) return problem(404, "application-not-found");
      const body = (await request.json()) as ApplicationContactsRequest;
      if (body.basedOnVersion !== application.version) return problem(409, "version-conflict");
      const contactIds = [...new Set(body.contactIds)];
      if (contactIds.length > MAX_CONTACTS) return refused([{ field: "contactIds", problem: "TOO_MANY" }]);
      const known = state.knownContactIds?.();
      if (known && contactIds.some((id) => !known.includes(id)))
        return refused([{ field: "contactIds", problem: "NOT_FOUND" }]);
      state.contactLinks.push(body);
      return store({ ...application, contactIds, version: application.version + 1 });
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
