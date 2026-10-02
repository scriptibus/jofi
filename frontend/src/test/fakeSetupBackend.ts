// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the AI setup API (#23, #24) as MSW handlers, next to `fakeAuthBackend`. It
// mirrors the backend's status codes, problem types and rules the UI depends on (key re-entry on an
// origin change, 409 while tasks use a provider, the two-step delete, 502 on a failed connection test,
// `capMicros` required and nullable); the real flow runs in the e2e suite (tests/ai).

import { HttpResponse, http } from "msw";
import type {
  CapabilityNeedsResponse,
  CostSummaryResponse,
  CostTotalsResponse,
  ModelPriceResponse,
  ModelResponse,
  ProviderPrivacyEntryResponse,
  ProviderPrivacyResponse,
  ProviderResponse,
  TaskAssignmentResponse,
  TaskAssignmentResponseTask,
} from "../api/generated/jofi";

const origin = () => window.location.origin;
const json = (body: unknown, status = 200) => HttpResponse.json(body as never, { status });
const problem = (status: number, type: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json(
    { type, title: "Problem", status, detail: type, ...extra },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
const setup = (code: string) => `urn:jofi:problem:setup:${code}`;
const invalid = (violations: { field: string; problem: string }[]) =>
  problem(400, setup("invalid-input"), { violations });

const LONG = 32_768;
const BASIC = 8_192;
/** What each task needs (backend `CapabilityCheck`). */
export const NEEDS: Record<TaskAssignmentResponseTask, CapabilityNeedsResponse> = {
  SCANNER_PRE_SCORING: { features: [], minContextWindowTokens: BASIC },
  CLASSIFICATION: { features: [], minContextWindowTokens: BASIC },
  LANGUAGE_TONE_DETECTION: { features: [], minContextWindowTokens: BASIC },
  EXTRACTION: { features: [], minContextWindowTokens: LONG },
  KNOWLEDGE_INTERVIEW: { features: ["TOOL_USE", "STREAMING"], minContextWindowTokens: LONG },
  DOCUMENT_GENERATION: { features: [], minContextWindowTokens: LONG },
  INTERVIEW_TRAINING: { features: ["STREAMING"], minContextWindowTokens: LONG },
  CHAT: { features: ["TOOL_USE", "STREAMING"], minContextWindowTokens: LONG },
  EMBEDDING: { features: ["EMBEDDING"], minContextWindowTokens: null },
  SPEECH_TO_TEXT: { features: ["SPEECH_TO_TEXT", "STREAMING"], minContextWindowTokens: null },
  TEXT_TO_SPEECH: { features: ["TEXT_TO_SPEECH", "STREAMING"], minContextWindowTokens: null },
};
const TASKS = Object.keys(NEEDS) as TaskAssignmentResponseTask[];

export function model(name: string, features: ModelResponse["features"] = [], context: number | null = null) {
  return {
    model: name,
    features,
    contextWindowTokens: context,
    origin: "DETECTED",
    updatedAt: "2026-09-30T12:00:00Z",
  } satisfies ModelResponse;
}

function claim(
  status: ProviderPrivacyEntryResponse["zeroDataRetention"]["status"],
  en: string,
  de: string,
  source: string,
) {
  return { status, summary: { en, de }, evidence: [{ source, quote: `Quote from ${source}` }] };
}

function privacyEntry(
  kind: ProviderPrivacyEntryResponse["kind"],
  stale = false,
): ProviderPrivacyEntryResponse {
  const docs = `https://docs.example.com/${kind.toLowerCase()}/privacy`;
  return {
    kind,
    checkedOn: stale ? "2025-01-15" : "2026-09-30",
    stale,
    zeroDataRetention: claim(
      "ON_REQUEST",
      `ZDR for ${kind} on request.`,
      `ZDR für ${kind} auf Anfrage.`,
      docs,
    ),
    noTraining: claim("YES", "API data is not used for training.", "API-Daten werden nicht trainiert.", docs),
    dataLocation: claim("UNKNOWN", "Not stated.", "Nicht angegeben.", `${docs}/regions`),
  };
}

/** Privacy info like `GET /api/setup/providers/privacy` (ADR-0045); `staleKinds` are read too long ago. */
export function privacyInfo(
  staleKinds: ProviderPrivacyEntryResponse["kind"][] = [],
): ProviderPrivacyResponse {
  const kinds = ["ANTHROPIC", "OPENAI", "GEMINI", "MISTRAL", "OPENAI_COMPATIBLE"] as const;
  return {
    checkedOn: "2026-09-30",
    staleAfterMonths: 6,
    disclaimer: { key: "setup_provider_privacy_disclaimer", text: { en: "Server text", de: "Servertext" } },
    providers: kinds.map((kind) => privacyEntry(kind, staleKinds.includes(kind))),
  };
}

/** One month's costs as `GET /api/setup/costs` breaks them down (the budget and the month are added by the fake). */
export type Breakdown = Pick<CostSummaryResponse, "byTask" | "byProviderKind" | "byModel" | "total">;

export const NO_CALLS: CostTotalsResponse = {
  calls: 0,
  inputTokens: 0,
  outputTokens: 0,
  knownCostMicros: 0,
  unknownCostCalls: 0,
};

/** Totals of a cost line; `unknown` calls have no price and are not part of `knownCostMicros`. */
export function totals(
  calls: number,
  knownCostMicros: number,
  unknown = 0,
  tokens: [number, number] = [calls * 100, calls * 20],
): CostTotalsResponse {
  return {
    calls,
    inputTokens: tokens[0],
    outputTokens: tokens[1],
    knownCostMicros,
    unknownCostCalls: unknown,
  };
}

const FIRST_YEAR = 2000;
const MAX_HISTORY_MONTHS = 24;
const MONTHS_A_YEAR = 12;
const MONTH_FORMAT = /^(\d{4})-(0[1-9]|1[0-2])$/;

/** `month` moved by `delta` months (`2026-01`, -1 -> `2025-12`). */
export function shiftMonth(month: string, delta: number): string {
  const [year = 0, number = 0] = month.split("-").map(Number);
  const index = year * MONTHS_A_YEAR + (number - 1) + delta;
  return `${Math.floor(index / MONTHS_A_YEAR)}-${String((index % MONTHS_A_YEAR) + 1).padStart(2, "0")}`;
}

export interface FakeSetupState {
  /** The privacy info; null answers 404, so the UI falls back to what is certain. */
  privacy: ProviderPrivacyResponse | null;
  providers: ProviderResponse[];
  /** Keys as the server stored them: the UI must never get them back. */
  keys: Map<string, string>;
  models: Map<string, ModelResponse[]>;
  /** What a connection test lists, per provider id (default: `listed`). */
  listed: ModelResponse[];
  /** A connection test answers 502 with this setup problem code. */
  refreshFails?: string;
  /** Assigning these tasks answers 503 `storage-unavailable`. */
  assignFails?: TaskAssignmentResponseTask[];
  assignments: Map<TaskAssignmentResponseTask, { providerId: string; model: string }>;
  capMicros: number | null;
  spentMicros: number;
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** Budget bodies received, to check `capMicros` is always sent. */
  budgetBodies: unknown[];
  /** The user's model prices per provider id (#142). */
  prices: Map<string, ModelPriceResponse[]>;
  /** Price bodies received by `PUT .../model-prices`. */
  priceBodies: unknown[];
  /** The month the server calls current (UTC). */
  currentMonth: string;
  /** Costs per month (`YYYY-MM`); a month without an entry has no calls. */
  costs: Record<string, Breakdown>;
  /** Cost requests seen, as `summary:<month or ->` and `history:<months>`. */
  costCalls: string[];
  /** The summary or the history answers this status (a 500 for the retry path). */
  costsFail?: { summary?: number; history?: number };
}

const MAX_PRICE_MICROS = 10_000_000_000;

function missing(needs: CapabilityNeedsResponse, found: ModelResponse | undefined): CapabilityNeedsResponse {
  const context = needs.minContextWindowTokens ?? null;
  return {
    features: needs.features.filter((feature) => !found?.features.includes(feature)),
    minContextWindowTokens: context !== null && (found?.contextWindowTokens ?? 0) < context ? context : null,
  };
}

/** The violations the backend's `ModelPriceInput` finds, in its order: model, input, output. */
function priceViolations(body: {
  model?: string | null;
  inputMicrosPerMillion?: number | null;
  outputMicrosPerMillion?: number | null;
}) {
  const violations: { field: string; problem: string }[] = [];
  if (!body.model?.trim()) violations.push({ field: "model", problem: "REQUIRED" });
  for (const field of ["inputMicrosPerMillion", "outputMicrosPerMillion"] as const) {
    const value = body[field];
    if (value == null) violations.push({ field, problem: "REQUIRED" });
    else if (value < 0 || value > MAX_PRICE_MICROS) violations.push({ field, problem: "OUT_OF_RANGE" });
  }
  return violations;
}

function originOf(url: string | null | undefined) {
  return url ? new URL(url).origin : null;
}

export function fakeSetupBackend(initial: Partial<FakeSetupState> = {}) {
  const state: FakeSetupState = {
    privacy: privacyInfo(),
    providers: [],
    keys: new Map(),
    models: new Map(),
    listed: [],
    assignments: new Map(),
    capMicros: null,
    spentMicros: 0,
    deleteCalls: [],
    budgetBodies: [],
    prices: new Map(),
    priceBodies: [],
    currentMonth: "2026-09",
    costs: {},
    costCalls: [],
    ...initial,
  };
  let next = 1;

  const assignment = (task: TaskAssignmentResponseTask): TaskAssignmentResponse => {
    const assigned = state.assignments.get(task);
    const found = assigned
      ? state.models.get(assigned.providerId)?.find((entry) => entry.model === assigned.model)
      : undefined;
    return {
      task,
      providerId: assigned?.providerId ?? null,
      model: assigned?.model ?? null,
      needs: NEEDS[task],
      missing: missing(NEEDS[task], found),
    };
  };

  const budget = () => ({
    month: "2026-09",
    currency: "USD",
    capMicros: state.capMicros,
    spentMicros: state.spentMicros,
    remainingMicros: state.capMicros === null ? null : Math.max(0, state.capMicros - state.spentMicros),
    state:
      state.capMicros === null
        ? "NO_CAP"
        : state.spentMicros >= state.capMicros
          ? "REACHED"
          : "WITHIN_BUDGET",
    pausedTasks: [],
    pausedUntil:
      state.capMicros !== null && state.spentMicros >= state.capMicros ? "2026-10-01T00:00:00Z" : null,
  });

  const emptyMonth: Breakdown = { byTask: [], byProviderKind: [], byModel: [], total: NO_CALLS };

  const summary = (url: URL) => {
    const requested = url.searchParams.get("month");
    state.costCalls.push(`summary:${requested ?? "-"}`);
    if (state.costsFail?.summary) return problem(state.costsFail.summary, "about:blank");
    const month = requested ?? state.currentMonth;
    const year = Number(month.slice(0, 4));
    // The backend (`BillingMonth`): a malformed value is INVALID_FORMAT, a well-formed one before 2000 or
    // after the current month is OUT_OF_RANGE.
    if (!MONTH_FORMAT.test(month)) return invalid([{ field: "month", problem: "INVALID_FORMAT" }]);
    if (month > state.currentMonth || year < FIRST_YEAR)
      return invalid([{ field: "month", problem: "OUT_OF_RANGE" }]);
    const isCurrent = month === state.currentMonth;
    return json({
      month,
      currency: "USD",
      ...(state.costs[month] ?? emptyMonth),
      ...(isCurrent ? { budget: { ...budget(), month } } : {}),
    });
  };

  const history = (url: URL) => {
    const requested = url.searchParams.get("months") ?? "12";
    const count = Number(requested);
    state.costCalls.push(`history:${requested}`);
    if (state.costsFail?.history) return problem(state.costsFail.history, "about:blank");
    // The backend (`CostReport.MAX_MONTHS`): 1 to 24, anything else is refused; a non-number is a plain 400.
    if (!Number.isInteger(count)) return problem(400, "about:blank");
    if (count < 1 || count > MAX_HISTORY_MONTHS)
      return invalid([{ field: "months", problem: "OUT_OF_RANGE" }]);
    return json(
      Array.from({ length: count }, (_, index) => {
        const month = shiftMonth(state.currentMonth, index + 1 - count);
        return { month, totals: state.costs[month]?.total ?? NO_CALLS };
      }),
    );
  };

  const handlers = [
    http.get(`${origin()}/api/setup/costs/history`, ({ request }) => history(new URL(request.url))),
    http.get(`${origin()}/api/setup/costs`, ({ request }) => summary(new URL(request.url))),
    http.get(`${origin()}/api/setup/providers/privacy`, () =>
      state.privacy ? json(state.privacy) : problem(404, "about:blank"),
    ),
    http.get(`${origin()}/api/setup/providers`, () => json(state.providers)),
    http.post(`${origin()}/api/setup/providers`, async ({ request }) => {
      const body = (await request.json()) as {
        kind: ProviderResponse["kind"];
        displayName: string;
        baseUrl?: string | null;
        apiKey?: string | null;
      };
      if (body.kind !== "OPENAI_COMPATIBLE" && !body.apiKey)
        return invalid([{ field: "apiKey", problem: "REQUIRED" }]);
      const id = `00000000-0000-4000-8000-00000000000${next++}`;
      const provider = {
        id,
        kind: body.kind,
        displayName: body.displayName,
        baseUrl: body.baseUrl ?? null,
        apiKeySet: Boolean(body.apiKey),
      };
      if (body.apiKey) state.keys.set(id, body.apiKey);
      state.providers.push(provider);
      return json(provider, 201);
    }),
    http.put(`${origin()}/api/setup/providers/:id`, async ({ request, params }) => {
      const provider = state.providers.find((entry) => entry.id === params.id);
      if (!provider) return problem(404, setup("provider-not-found"));
      const body = (await request.json()) as {
        displayName: string;
        baseUrl?: string | null;
        apiKey?: string | null;
      };
      if (provider.apiKeySet && !body.apiKey && originOf(body.baseUrl) !== originOf(provider.baseUrl))
        return invalid([{ field: "apiKey", problem: "REQUIRED" }]);
      Object.assign(provider, { displayName: body.displayName, baseUrl: body.baseUrl ?? null });
      if (body.apiKey) {
        state.keys.set(provider.id, body.apiKey);
        provider.apiKeySet = true;
      }
      return json(provider);
    }),
    http.delete(`${origin()}/api/setup/providers/:id`, ({ request, params }) => {
      const provider = state.providers.find((entry) => entry.id === params.id);
      if (!provider) return problem(404, setup("provider-not-found"));
      if ([...state.assignments.values()].some((entry) => entry.providerId === provider.id))
        return problem(409, setup("provider-in-use"));
      if (request.headers.get("Jofi-Confirmation") === null) {
        state.deleteCalls.push("first");
        return HttpResponse.json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: "delete-t0k3n",
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "setup.delete-provider",
            targets: [provider.id],
            effect: { kind: "ai_provider", name: provider.displayName, counts: {} },
          },
          { status: 428, headers: { "Content-Type": "application/problem+json" } },
        );
      }
      state.deleteCalls.push("confirmed");
      state.providers = state.providers.filter((entry) => entry.id !== provider.id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${origin()}/api/setup/providers/:id/models`, ({ params }) =>
      json(state.models.get(String(params.id)) ?? []),
    ),
    http.post(`${origin()}/api/setup/providers/:id/models/refresh`, ({ params }) => {
      if (state.refreshFails) return problem(502, setup(state.refreshFails));
      state.models.set(String(params.id), state.listed);
      return json(state.listed);
    }),
    // The user's model prices: only OpenAI-compatible providers take one (409 otherwise), as the backend does.
    http.get(`${origin()}/api/setup/providers/:id/model-prices`, ({ params }) =>
      state.providers.some((entry) => entry.id === params.id)
        ? json(state.prices.get(String(params.id)) ?? [])
        : problem(404, setup("provider-not-found")),
    ),
    http.put(`${origin()}/api/setup/providers/:id/model-prices`, async ({ request, params }) => {
      const provider = state.providers.find((entry) => entry.id === params.id);
      if (!provider) return problem(404, setup("provider-not-found"));
      const body = (await request.json()) as {
        model?: string | null;
        inputMicrosPerMillion?: number | null;
        outputMicrosPerMillion?: number | null;
      };
      state.priceBodies.push(body);
      const violations = priceViolations(body);
      if (violations.length > 0) return invalid(violations);
      if (provider.kind !== "OPENAI_COMPATIBLE") return problem(409, setup("price-not-allowed"));
      const price = {
        model: (body.model ?? "").trim(),
        inputMicrosPerMillion: body.inputMicrosPerMillion ?? 0,
        outputMicrosPerMillion: body.outputMicrosPerMillion ?? 0,
        updatedAt: "2026-10-02T12:00:00Z",
      } satisfies ModelPriceResponse;
      const others = (state.prices.get(provider.id) ?? []).filter((entry) => entry.model !== price.model);
      state.prices.set(
        provider.id,
        [...others, price].sort((a, b) => a.model.localeCompare(b.model)),
      );
      return json(price);
    }),
    http.delete(`${origin()}/api/setup/providers/:id/model-prices`, ({ request, params }) => {
      if (!state.providers.some((entry) => entry.id === params.id))
        return problem(404, setup("provider-not-found"));
      const model = new URL(request.url).searchParams.get("model") ?? "";
      state.prices.set(
        String(params.id),
        (state.prices.get(String(params.id)) ?? []).filter((entry) => entry.model !== model),
      );
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${origin()}/api/setup/assignments`, () => json(TASKS.map(assignment))),
    http.put(`${origin()}/api/setup/assignments/:task`, async ({ request, params }) => {
      const task = params.task as TaskAssignmentResponseTask;
      if (state.assignFails?.includes(task)) return problem(503, setup("storage-unavailable"));
      state.assignments.set(task, (await request.json()) as { providerId: string; model: string });
      return json(assignment(task));
    }),
    http.get(`${origin()}/api/setup/budget`, () => json(budget())),
    http.put(`${origin()}/api/setup/budget`, async ({ request }) => {
      const body = (await request.json()) as { capMicros?: number | null };
      state.budgetBodies.push(body);
      if (!("capMicros" in body)) return invalid([{ field: "capMicros", problem: "REQUIRED" }]);
      const cap = body.capMicros ?? null;
      if (cap !== null && (cap < 1 || cap > 1_000_000_000_000))
        return invalid([{ field: "capMicros", problem: "OUT_OF_RANGE" }]);
      state.capMicros = cap;
      return json(budget());
    }),
  ];

  return { state, handlers };
}
