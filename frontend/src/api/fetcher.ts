// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ProblemDetail } from "./generated/jofi";

/** A non-2xx answer from the backend, carrying its RFC 9457 problem details. */
export class ApiProblemError<P extends ProblemDetail = ProblemDetail> extends Error {
  readonly status: number;
  readonly problem: P;

  constructor(status: number, problem: P) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    this.name = "ApiProblemError";
    this.status = status;
    this.problem = problem;
  }
}

/** The error type of every generated hook (orval picks up this export from the mutator). */
export type ErrorType<P> = ApiProblemError<P extends ProblemDetail ? P : ProblemDetail>;

async function readProblem(response: Response): Promise<ProblemDetail> {
  const isJson = response.headers.get("content-type")?.includes("json") ?? false;
  const body: unknown = isJson ? await response.json().catch(() => undefined) : undefined;
  return typeof body === "object" && body !== null ? (body as ProblemDetail) : { status: response.status };
}

/**
 * The fetch every generated operation goes through (orval `mutator`). Paths are relative to the
 * app's origin: the backend serves the SPA and the API from the same host.
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(new URL(path, window.location.origin), init);
  if (!response.ok) {
    throw new ApiProblemError(response.status, await readProblem(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
