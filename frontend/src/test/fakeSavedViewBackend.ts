// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the saved views of the application list (`/api/applications/saved-views`, #99,
// ADR-0050), as MSW handlers. It follows the backend's rules the UI relies on: a name is required and unique
// ignoring case (400 `invalid-saved-view` with `name` `REQUIRED`/`TAKEN`), a stale `basedOnVersion` is a 409
// `version-conflict`, an unknown id a 404, and a delete answers 428 with a token first (ADR-0039).

import { HttpResponse, http } from "msw";
import type { SavedViewRequest, SavedViewResponse, UpdateSavedViewRequest } from "../api/generated/jofi";

const origin = () => window.location.origin;
const DELETE_TOKEN = "saved-view-delete-token";

export function aSavedView(overrides: Partial<SavedViewResponse> = {}): SavedViewResponse {
  return {
    id: crypto.randomUUID(),
    name: "Applied at ACME",
    filter: {},
    adjusted: false,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

export interface FakeSavedViewState {
  views: SavedViewResponse[];
  /** Answer every list request with this status instead (a 4xx: no retries). */
  failWith: number | null;
  /** While set, the list waits for this promise before it answers (to see "Loading…"). */
  listGate: Promise<void> | null;
  /** Every accepted create and update body, in order. */
  saved: (SavedViewRequest | UpdateSavedViewRequest)[];
  /** Every delete call: `first` (answered 428) or `confirmed`. */
  deleteCalls: ("first" | "confirmed")[];
}

const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });

const problem = (status: number, code: string, violations?: { field: string; problem: string }[]) =>
  json(
    { type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code, violations },
    status,
  );

/** The name rules (backend `SavedViewInput.validate` and the use cases' name check). */
function nameProblem(state: FakeSavedViewState, name: string, id?: string): string | undefined {
  if (name.trim() === "") return "REQUIRED";
  const taken = state.views.some(
    (view) => view.id !== id && view.name.toLowerCase() === name.trim().toLowerCase(),
  );
  return taken ? "TAKEN" : undefined;
}

const byName = (a: SavedViewResponse, b: SavedViewResponse) => a.name.localeCompare(b.name);

export function fakeSavedViewBackend(initial: Partial<FakeSavedViewState> = {}) {
  const state: FakeSavedViewState = {
    views: [],
    failWith: null,
    listGate: null,
    saved: [],
    deleteCalls: [],
    ...initial,
  };
  const base = () => `${origin()}/api/applications/saved-views`;

  const handlers = [
    http.get(base(), async () => {
      await state.listGate;
      if (state.failWith !== null)
        return json({ title: "Unavailable", status: state.failWith }, state.failWith);
      return HttpResponse.json({ views: [...state.views].sort(byName) });
    }),
    http.post(base(), async ({ request }) => {
      const body = (await request.json()) as SavedViewRequest;
      const invalid = nameProblem(state, body.name);
      if (invalid) return problem(400, "invalid-saved-view", [{ field: "name", problem: invalid }]);
      state.saved.push(body);
      const now = new Date().toISOString();
      const view = aSavedView({
        name: body.name.trim(),
        filter: body.filter,
        createdAt: now,
        updatedAt: now,
      });
      state.views.push(view);
      return HttpResponse.json(view, { status: 201 });
    }),
    http.put(`${base()}/:id`, async ({ request, params }) => {
      const body = (await request.json()) as UpdateSavedViewRequest;
      const index = state.views.findIndex((view) => view.id === params.id);
      const stored = state.views[index];
      if (!stored) return problem(404, "saved-view-not-found");
      if (body.basedOnVersion !== stored.version) return problem(409, "version-conflict");
      const invalid = nameProblem(state, body.view.name, stored.id);
      if (invalid) return problem(400, "invalid-saved-view", [{ field: "name", problem: invalid }]);
      state.saved.push(body);
      const view = {
        ...stored,
        name: body.view.name.trim(),
        filter: body.view.filter,
        adjusted: false,
        version: stored.version + 1,
        updatedAt: new Date().toISOString(),
      };
      state.views[index] = view;
      return HttpResponse.json(view);
    }),
    http.delete(`${base()}/:id`, ({ request, params }) => {
      const view = state.views.find((candidate) => candidate.id === params.id);
      if (!view) return problem(404, "saved-view-not-found");
      if (request.headers.get("Jofi-Confirmation") !== DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "saved-views.delete",
            targets: [view.id],
            effect: { kind: "saved_view", name: view.name, counts: {} },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.views = state.views.filter((other) => other.id !== view.id);
      return new HttpResponse(null, { status: 204 });
    }),
  ];

  return { state, handlers };
}
