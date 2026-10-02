// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the task endpoints (#93, #94, #95), as MSW handlers. It mirrors the backend's status
// codes and problem types: 400 violations, 409 `version-conflict` for a stale `basedOnVersion`, 409
// `invalid-transition` for accepting or dismissing what is no suggestion, a 428 before a delete, the grouped list
// of open tasks only, and the suggestions (state `SUGGESTED`) newest first. Both lists are paged like the server's
// (ADR-0056: `page` from 0, `size` up to 50, default 20) and show an excerpt of the notes, never the notes. The group of each task is given by the test (`groups`),
// or follows the bucket it was created with; the real calendar runs in the backend's tests and in e2e.

import { HttpResponse, http } from "msw";
import type {
  TaskGroupResponseGroup,
  TaskRequest,
  TaskResponse,
  TaskSummaryResponse,
  TaskTimingRequest,
  TaskTimingResponse,
  TaskVersionRequest,
  UpdateTaskRequest,
} from "../api/generated/jofi";

const origin = () => window.location.origin;
const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
const problem = (status: number, code: string) =>
  json({ type: `urn:jofi:problem:tasks:${code}`, title: "Problem", status, detail: code }, status);

export const TASK_DELETE_TOKEN = "task-delete-t0k3n";

const GROUP_ORDER: TaskGroupResponseGroup[] = [
  "OVERDUE",
  "TODAY",
  "THIS_WEEK",
  "NEXT_WEEK",
  "THIS_MONTH",
  "LATER",
  "SOMEDAY",
];

export function aTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: crypto.randomUUID(),
    title: "Send the follow-up",
    timing: { span: "WEEK", startsOn: "2026-09-28", endsBefore: "2026-10-05" },
    link: null,
    notes: null,
    origin: "MANUAL",
    suggestionRule: null,
    status: "OPEN",
    completedAt: null,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

/** The server's excerpt length (backend `TextExcerpt.MAX_LENGTH`). */
export const EXCERPT_LENGTH = 300;
const DEFAULT_PAGE_SIZE = 20;

/** A task as the lists show it: the notes cut to an excerpt, under keys of their own. */
export function summaryOfTask(task: TaskResponse): TaskSummaryResponse {
  const { notes, ...rest } = task;
  const points = notes === null || notes === undefined ? null : [...notes];
  return {
    ...rest,
    notesExcerpt: points ? points.slice(0, EXCERPT_LENGTH).join("") : null,
    notesTruncated: points ? points.length > EXCERPT_LENGTH : false,
  };
}

function pageOf<T>(all: T[], url: string) {
  const query = new URL(url).searchParams;
  const page = Number(query.get("page") ?? 0);
  const size = Number(query.get("size") ?? DEFAULT_PAGE_SIZE);
  const start = page * size;
  return {
    items: all.slice(start, start + size),
    info: { page, size, total: all.length, hasMore: start + size < all.length },
  };
}

export interface FakeTaskState {
  tasks: TaskResponse[];
  /** The group each task is listed in, by id; tasks without one follow their bucket (see `groupOf`). */
  groups: Record<string, TaskGroupResponseGroup>;
  /** The `timeZone` of every list request, in order. */
  listZones: string[];
  /** The `page` and `size` of every grouped list request, in order. */
  listPages: { page: number; size: number }[];
  /** The `page` and `size` of every suggestions request, in order. */
  suggestionPages: { page: number; size: number }[];
  /** Every accepted `POST` body, in order. */
  creates: TaskRequest[];
  /** Every accepted `PUT` body, in order. */
  updates: UpdateTaskRequest[];
  /** Every complete (`true`) and reopen (`false`) call's body, in order. */
  stateChanges: { done: boolean; basedOnVersion: number }[];
  /** Every accepted accept and dismiss call, in order. */
  decisions: { decision: "accept" | "dismiss"; id: string; basedOnVersion: number }[];
  /** While true, the suggestions list answers 503 `storage-unavailable`. */
  suggestionsUnavailable: boolean;
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** Held until resolved: lets a test look at the page while a complete is on its way. */
  completeGate?: Promise<void>;
}

function groupOf(timing: TaskTimingRequest): TaskGroupResponseGroup {
  if (timing.localDue) return "THIS_WEEK";
  return timing.bucket === "SOMEDAY" ? "SOMEDAY" : (timing.bucket ?? "SOMEDAY");
}

const spans = {
  TODAY: "DAY",
  THIS_WEEK: "WEEK",
  NEXT_WEEK: "WEEK",
  THIS_MONTH: "MONTH",
  SOMEDAY: "SOMEDAY",
} as const;

/** What the server answers for the timing sent (the dates are fixed; the tests do not look at them). */
function timingOf(timing: TaskTimingRequest): TaskTimingResponse {
  if (timing.localDue)
    return { dueAt: `${timing.localDue}:00Z`, localDue: timing.localDue, timeZone: timing.timeZone };
  const span = spans[timing.bucket ?? "SOMEDAY"];
  return span === "SOMEDAY" ? { span } : { span, startsOn: "2026-09-28", endsBefore: "2026-10-05" };
}

function violations(details: TaskRequest) {
  const found: { field: string; problem: string }[] = [];
  if (details.title.trim() === "") found.push({ field: "title", problem: "REQUIRED" });
  if (details.timing.localDue?.startsWith("1999"))
    found.push({ field: "timing.localDue", problem: "OUT_OF_RANGE" });
  return found;
}

function withDetails(task: TaskResponse, details: TaskRequest): TaskResponse {
  return {
    ...task,
    title: details.title,
    notes: details.notes ?? null,
    link: details.link ?? null,
    timing: timingOf(details.timing),
  };
}

export function fakeTaskBackend(initial: Partial<FakeTaskState> = {}) {
  const state: FakeTaskState = {
    tasks: [],
    groups: {},
    listZones: [],
    listPages: [],
    suggestionPages: [],
    creates: [],
    updates: [],
    stateChanges: [],
    decisions: [],
    suggestionsUnavailable: false,
    deleteCalls: [],
    ...initial,
  };
  const find = (id: unknown) => state.tasks.find((task) => task.id === id);
  const store = (saved: TaskResponse) => {
    state.tasks = state.tasks.map((other) => (other.id === saved.id ? saved : other));
    return HttpResponse.json(saved);
  };
  const refused = (found: { field: string; problem: string }[]) =>
    json({ type: "urn:jofi:problem:tasks:invalid-task", status: 400, violations: found }, 400);

  const move =
    (done: boolean) =>
    async ({ request, params }: { request: Request; params: Record<string, unknown> }) => {
      if (done && state.completeGate) await state.completeGate;
      const task = find(params.id);
      if (!task) return problem(404, "task-not-found");
      const { basedOnVersion } = (await request.json()) as TaskVersionRequest;
      if (basedOnVersion !== task.version) return problem(409, "version-conflict");
      state.stateChanges.push({ done, basedOnVersion });
      return store({
        ...task,
        status: done ? "DONE" : "OPEN",
        completedAt: done ? "2026-09-30T12:00:00Z" : null,
        version: task.version + 1,
      });
    };

  const decide =
    (decision: "accept" | "dismiss") =>
    async ({ request, params }: { request: Request; params: Record<string, unknown> }) => {
      const task = find(params.id);
      if (!task) return problem(404, "task-not-found");
      const { basedOnVersion } = (await request.json()) as TaskVersionRequest;
      if (basedOnVersion !== task.version) return problem(409, "version-conflict");
      if (task.status !== "SUGGESTED") return problem(409, "invalid-transition");
      state.decisions.push({ decision, id: task.id, basedOnVersion });
      return store({
        ...task,
        status: decision === "accept" ? "OPEN" : "DISMISSED",
        version: task.version + 1,
      });
    };

  const handlers = [
    // Before `/api/tasks/:id`, which would take "suggestions" for an id.
    http.get(`${origin()}/api/tasks/suggestions`, ({ request }) => {
      if (state.suggestionsUnavailable) return problem(503, "storage-unavailable");
      const suggested = state.tasks.filter((task) => task.status === "SUGGESTED").toReversed();
      const { items, info } = pageOf(suggested, request.url);
      state.suggestionPages.push({ page: info.page, size: info.size });
      return HttpResponse.json({ tasks: items.map(summaryOfTask), page: info });
    }),
    http.post(`${origin()}/api/tasks/:id/accept`, decide("accept")),
    http.post(`${origin()}/api/tasks/:id/dismiss`, decide("dismiss")),
    http.get(`${origin()}/api/tasks`, ({ request }) => {
      state.listZones.push(new URL(request.url).searchParams.get("timeZone") ?? "");
      const open = state.tasks.filter((task) => task.status === "OPEN");
      const inOrder = GROUP_ORDER.flatMap((group) =>
        open
          .filter((task) => (state.groups[task.id] ?? "THIS_WEEK") === group)
          .map((task) => ({ group, task })),
      );
      const { items, info } = pageOf(inOrder, request.url);
      state.listPages.push({ page: info.page, size: info.size });
      const groups = GROUP_ORDER.map((group) => ({
        group,
        tasks: items.filter((item) => item.group === group).map((item) => summaryOfTask(item.task)),
      }));
      return HttpResponse.json({ groups, page: info });
    }),
    http.post(`${origin()}/api/tasks`, async ({ request }) => {
      const body = (await request.json()) as TaskRequest;
      const found = violations(body);
      if (found.length > 0) return refused(found);
      state.creates.push(body);
      const created = withDetails(aTask(), body);
      state.tasks = [...state.tasks, created];
      state.groups[created.id] = groupOf(body.timing);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.get(`${origin()}/api/tasks/:id`, ({ params }) => {
      const task = find(params.id);
      return task ? HttpResponse.json(task) : problem(404, "task-not-found");
    }),
    http.put(`${origin()}/api/tasks/:id`, async ({ request, params }) => {
      const task = find(params.id);
      if (!task) return problem(404, "task-not-found");
      const body = (await request.json()) as UpdateTaskRequest;
      const found = violations(body.details);
      if (found.length > 0) return refused(found);
      if (body.basedOnVersion !== task.version) return problem(409, "version-conflict");
      state.updates.push(body);
      state.groups[task.id] = groupOf(body.details.timing);
      return store({ ...withDetails(task, body.details), version: task.version + 1 });
    }),
    http.post(`${origin()}/api/tasks/:id/complete`, move(true)),
    http.post(`${origin()}/api/tasks/:id/reopen`, move(false)),
    http.delete(`${origin()}/api/tasks/:id`, ({ request, params }) => {
      const task = find(params.id);
      if (!task) return problem(404, "task-not-found");
      if (request.headers.get("Jofi-Confirmation") !== TASK_DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: TASK_DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "tasks.delete",
            targets: [task.id],
            effect: { kind: "task", name: task.title, counts: {} },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.tasks = state.tasks.filter((other) => other.id !== task.id);
      return new HttpResponse(null, { status: 204 });
    }),
  ];

  return { state, handlers };
}
