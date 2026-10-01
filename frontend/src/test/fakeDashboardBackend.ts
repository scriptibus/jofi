// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the dashboard's endpoints (#113, ADR-0052) and the AI cost summary (#24), as MSW
// handlers. Each figure can be made to fail with 503 on its own (`unavailable`), as the backend's can. The counts
// themselves are given by the test; the real queries run in the backend's tests and in e2e.

import { HttpResponse, http } from "msw";
import type {
  ActivityEntryResponse,
  CostSummaryResponse,
  FunnelDto,
  PipelineOverviewResponse,
  StatusCountDto,
  TaskDashboardResponse,
} from "../api/generated/jofi";

const origin = () => window.location.origin;

const STATUSES: StatusCountDto["status"][] = [
  "DISCOVERED",
  "SHORTLISTED",
  "PREPARING",
  "APPLIED",
  "INTERVIEWING",
  "OFFER",
  "ACCEPTED",
  "REJECTED",
  "WITHDRAWN",
  "DECLINED",
  "GHOSTED",
];

const NO_FUNNEL: FunnelDto = {
  applied: 0,
  interviewed: 0,
  offered: 0,
  responded: 0,
  interviewRate: null,
  offerRate: null,
  responseRate: null,
};

/** All eleven statuses in pipeline order, as the server sends them; `counts` sets those above zero. */
export function aPipeline(
  counts: Partial<Record<StatusCountDto["status"], number>> = {},
  funnel: Partial<FunnelDto> = {},
  unread = 0,
): PipelineOverviewResponse {
  return {
    byStatus: STATUSES.map((status) => ({ status, count: counts[status] ?? 0 })),
    funnel: { ...NO_FUNNEL, ...funnel },
    unread,
  };
}

export function anActivityEntry(overrides: Partial<ActivityEntryResponse> = {}): ActivityEntryResponse {
  return {
    id: 1,
    occurredAt: "2026-09-30T10:00:00Z",
    actor: { kind: "USER", name: null },
    entityType: "application",
    entityId: crypto.randomUUID(),
    description: "Created application",
    fields: [],
    application: null,
    ...overrides,
  };
}

/** This month's cost summary: `spentMicros` known cost, and a budget when `capMicros` is given. */
export function aCostSummary(spentMicros = 0, capMicros: number | null = null, unknownCostCalls = 0) {
  const reached = capMicros !== null && spentMicros >= capMicros;
  const summary: CostSummaryResponse = {
    month: "2026-10",
    currency: "USD",
    total: { calls: 3, inputTokens: 100, outputTokens: 50, knownCostMicros: spentMicros, unknownCostCalls },
    byTask: [],
    byModel: [],
    byProviderKind: [],
    budget: {
      month: "2026-10",
      currency: "USD",
      capMicros,
      spentMicros,
      remainingMicros: capMicros === null ? null : Math.max(0, capMicros - spentMicros),
      state: capMicros === null ? "NO_CAP" : reached ? "REACHED" : "WITHIN_BUDGET",
      pausedTasks: [],
      pausedUntil: reached ? "2026-11-01T00:00:00Z" : null,
    },
  };
  return summary;
}

export type DashboardFigure = "pipeline" | "activity" | "tasks" | "costs";

export interface FakeDashboardState {
  pipeline: PipelineOverviewResponse;
  activity: ActivityEntryResponse[];
  tasks: TaskDashboardResponse;
  costs: CostSummaryResponse;
  /** The figures that answer 503. */
  unavailable: Set<DashboardFigure>;
  /** The `timeZone` of each task request and the `limit` of each activity request. */
  taskZones: string[];
  activityLimits: string[];
}

/** A fresh instance: no applications, no activity, no tasks, no AI cost and no budget. */
export function fakeDashboardBackend(initial: Partial<FakeDashboardState> = {}) {
  const state: FakeDashboardState = {
    pipeline: aPipeline(),
    activity: [],
    tasks: { overdue: [], upcoming: [] },
    costs: aCostSummary(),
    unavailable: new Set(),
    taskZones: [],
    activityLimits: [],
    ...initial,
  };
  const answer = (figure: DashboardFigure, body: object) =>
    state.unavailable.has(figure)
      ? HttpResponse.json(
          { type: "urn:jofi:problem:storage-unavailable", title: "Unavailable", status: 503 },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        )
      : HttpResponse.json(body);

  const handlers = [
    http.get(`${origin()}/api/dashboard/pipeline`, () => answer("pipeline", state.pipeline)),
    http.get(`${origin()}/api/dashboard/activity`, ({ request }) => {
      const limit = new URL(request.url).searchParams.get("limit") ?? "";
      state.activityLimits.push(limit);
      return answer("activity", { entries: state.activity.slice(0, Number(limit) || 20) });
    }),
    http.get(`${origin()}/api/dashboard/tasks`, ({ request }) => {
      state.taskZones.push(new URL(request.url).searchParams.get("timeZone") ?? "");
      return answer("tasks", state.tasks);
    }),
    http.get(`${origin()}/api/setup/costs`, () => answer("costs", state.costs)),
  ];
  return { state, handlers };
}
