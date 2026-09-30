// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiProblemError } from "../api/fetcher";
import { m } from "../paraglide/messages.js";

const SYSTEM = "urn:jofi:problem:system:";

/** Problem types the UI tells apart (backend: `AuthProblems`, ADR-0035). */
export const ProblemType = {
  notLoggedIn: `${SYSTEM}not-logged-in`,
  csrf: `${SYSTEM}csrf`,
  invalidCredentials: `${SYSTEM}invalid-credentials`,
  throttled: `${SYSTEM}login-throttled`,
  notSetUp: `${SYSTEM}not-set-up`,
  alreadySetUp: `${SYSTEM}already-set-up`,
  invalidSetupToken: `${SYSTEM}invalid-setup-token`,
  weakPassword: `${SYSTEM}weak-password`,
  unavailable: `${SYSTEM}auth-unavailable`,
  otherSessionsRemain: `${SYSTEM}other-sessions-remain`,
} as const;

/** Whether `error` is a problem-details answer of the given type. */
export function isProblem(error: unknown, type: string): error is ApiProblemError {
  return error instanceof ApiProblemError && error.problem.type === type;
}

/** 401 without a session: the session expired or was ended elsewhere. */
export function isSessionEnded(error: unknown): boolean {
  return isProblem(error, ProblemType.notLoggedIn);
}

/** Seconds until a throttled password check may be retried, or undefined when not throttled. */
export function throttledFor(error: unknown): number | undefined {
  if (!(error instanceof ApiProblemError) || error.status !== 429) return undefined;
  return error.retryAfterSeconds ?? 1;
}

export interface ErrorDescription {
  /** Localised message for the user. */
  message: string;
  /** The server's own `detail` (English), shown as secondary information for unknown problems. */
  detail?: string;
}

const knownMessages: Record<string, () => string> = {
  [ProblemType.csrf]: m.error_csrf,
  [ProblemType.unavailable]: m.error_unavailable,
  [ProblemType.weakPassword]: m.error_weak_password,
  [ProblemType.otherSessionsRemain]: m.error_other_sessions_remain,
  [ProblemType.notLoggedIn]: m.session_expired,
  [ProblemType.alreadySetUp]: m.first_run_done_elsewhere,
};

/**
 * Turns any error from the API client into a message in the user's language. Known problem types
 * get their own text; anything else a generic one with the status, plus the server's detail.
 * A failed `fetch` (no `ApiProblemError`) means the server was not reachable.
 */
export function describeError(error: unknown): ErrorDescription {
  if (!(error instanceof ApiProblemError)) return { message: m.error_offline() };
  const seconds = throttledFor(error);
  if (seconds !== undefined) return { message: m.throttled({ seconds }) };
  const known = error.problem.type === undefined ? undefined : knownMessages[error.problem.type];
  if (known) return { message: known() };
  if (error.status === 503) return { message: m.error_unavailable() };
  const description: ErrorDescription = { message: m.error_generic({ status: error.status }) };
  if (error.problem.detail) description.detail = error.problem.detail;
  return description;
}
