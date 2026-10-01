// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the posting import (`/api/applications/imports/…`, #96, #97, ADR-0051) as MSW
// handlers. It follows the backend's answers the UI relies on: `POST …/text` and `POST …/url` answer 202 with a
// PENDING import (or 200 with the SUCCEEDED import of a link imported before), 400 `invalid-application` with one
// `originalUrl` or `description` violation, 409 `ai-not-configured` and `import-in-progress`; `GET …/{id}` answers
// the import (PENDING for `pendingPolls` reads, then its outcome); `POST …/{id}/retry` queues a failed import again
// (409 `import-not-retryable` for any other). Posting text is never echoed, as the real answer never carries it.

import { HttpResponse, http } from "msw";
import type { PostingImportResponse, PostingImportResponseFailure } from "../api/generated/jofi";

const origin = () => window.location.origin;

export interface FakeImportState {
  /** Links imported before: a link import of one answers 200 with the existing application. */
  knownLinks: Record<string, string>;
  /** Links the fetch refuses: the violation code answered with 400 (`NOT_ALLOWED`, `TIMEOUT`, ...). */
  refusedLinks: Record<string, string>;
  /** The text import answers this violation (`REQUIRED`, `TOO_LONG`, ...) with 400. */
  textViolation: string | null;
  /** Answer every start with 409 `ai-not-configured` / `import-in-progress`, or 429 `import-busy` (the cap on concurrent fetches, no `Retry-After`). */
  startConflict: "ai-not-configured" | "import-in-progress" | "import-busy" | null;
  /** How many reads of a new import answer PENDING before it ends as `outcome`. */
  pendingPolls: number;
  /** How a new import ends once it is no longer pending. */
  outcome: { status: "SUCCEEDED"; applicationId: string } | { status: "FAILED"; failure: string };
  /** The next retry ends as this instead (the retried import then runs like a new one). */
  retryOutcome: FakeImportState["outcome"] | null;
  /** While set, a start waits for this promise before it answers (to see the pending state of the form). */
  startGate: Promise<void> | null;
  /** Answer every status read with this status as an error. */
  statusFailsWith: number | null;
  /** Every accepted start: the kind and the request body. */
  started: { kind: "url" | "text"; body: { url?: string; description?: string } }[];
  /** Every retry call, with the import's id. */
  retried: string[];
  /** Every status read, with the import's id. */
  reads: string[];
}

interface StoredImport {
  response: PostingImportResponse;
  outcome: FakeImportState["outcome"];
  pendingReads: number;
}

const NOW = "2026-10-01T10:00:00Z";

const problem = (status: number, code: string, violations?: { field: string; problem: string }[]) =>
  HttpResponse.json(
    { type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code, violations },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );

const invalid = (field: string, code: string) =>
  problem(400, "invalid-application", [{ field, problem: code }]);

export function fakeImportBackend(initial: Partial<FakeImportState> = {}) {
  const state: FakeImportState = {
    knownLinks: {},
    refusedLinks: {},
    textViolation: null,
    startConflict: null,
    pendingPolls: 1,
    outcome: { status: "SUCCEEDED", applicationId: "00000000-0000-4000-8000-000000000001" },
    retryOutcome: null,
    startGate: null,
    statusFailsWith: null,
    started: [],
    retried: [],
    reads: [],
    ...initial,
  };
  const imports = new Map<string, StoredImport>();

  const pending = (outcome: FakeImportState["outcome"]): StoredImport => {
    const response: PostingImportResponse = {
      id: crypto.randomUUID(),
      status: "PENDING",
      failure: null,
      applicationId: null,
      attempt: 1,
      createdAt: NOW,
      updatedAt: NOW,
    };
    const stored = { response, outcome, pendingReads: state.pendingPolls };
    imports.set(response.id, stored);
    return stored;
  };

  /** The import as a read answers it: PENDING until its reads ran out, then its outcome. */
  const read = (stored: StoredImport): PostingImportResponse => {
    if (stored.pendingReads > 0) {
      stored.pendingReads -= 1;
      return stored.response;
    }
    const { outcome } = stored;
    stored.response =
      outcome.status === "SUCCEEDED"
        ? { ...stored.response, status: "SUCCEEDED", applicationId: outcome.applicationId }
        : {
            ...stored.response,
            status: "FAILED",
            failure: outcome.failure as PostingImportResponseFailure,
          };
    return stored.response;
  };

  const handlers = [
    http.post(`${origin()}/api/applications/imports/text`, async ({ request }) => {
      await state.startGate;
      const body = (await request.json()) as { description: string };
      if (state.startConflict)
        return problem(state.startConflict === "import-busy" ? 429 : 409, state.startConflict);
      if (state.textViolation) return invalid("description", state.textViolation);
      state.started.push({ kind: "text", body });
      return HttpResponse.json(pending(state.outcome).response, { status: 202 });
    }),
    http.post(`${origin()}/api/applications/imports/url`, async ({ request }) => {
      await state.startGate;
      const body = (await request.json()) as { url: string };
      if (state.startConflict)
        return problem(state.startConflict === "import-busy" ? 429 : 409, state.startConflict);
      const refused = state.refusedLinks[body.url];
      if (refused) return invalid("originalUrl", refused);
      state.started.push({ kind: "url", body });
      const known = state.knownLinks[body.url];
      if (known) {
        const done: PostingImportResponse = {
          id: crypto.randomUUID(),
          status: "SUCCEEDED",
          failure: null,
          applicationId: known,
          attempt: 1,
          createdAt: NOW,
          updatedAt: NOW,
        };
        imports.set(done.id, { response: done, outcome: state.outcome, pendingReads: 0 });
        return HttpResponse.json(done, { status: 200 });
      }
      return HttpResponse.json(pending(state.outcome).response, { status: 202 });
    }),
    http.get(`${origin()}/api/applications/imports/:id`, ({ params }) => {
      const id = String(params.id);
      state.reads.push(id);
      if (state.statusFailsWith !== null)
        return HttpResponse.json(
          { title: "Unavailable", status: state.statusFailsWith },
          { status: state.statusFailsWith, headers: { "Content-Type": "application/problem+json" } },
        );
      const stored = imports.get(id);
      return stored ? HttpResponse.json(read(stored)) : problem(404, "import-not-found");
    }),
    http.post(`${origin()}/api/applications/imports/:id/retry`, ({ params }) => {
      const id = String(params.id);
      const stored = imports.get(id);
      if (!stored) return problem(404, "import-not-found");
      if (stored.response.status !== "FAILED") return problem(409, "import-not-retryable");
      state.retried.push(id);
      stored.outcome = state.retryOutcome ?? stored.outcome;
      stored.pendingReads = state.pendingPolls;
      stored.response = {
        ...stored.response,
        status: "PENDING",
        failure: null,
        attempt: stored.response.attempt + 1,
      };
      return HttpResponse.json(stored.response, { status: 202 });
    }),
  ];

  return { state, handlers };
}
