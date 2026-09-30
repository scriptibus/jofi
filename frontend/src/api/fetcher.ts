// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ProblemDetail } from "./generated/jofi";

/** A non-2xx answer from the backend, carrying its RFC 9457 problem details. */
export class ApiProblemError<P extends ProblemDetail = ProblemDetail> extends Error {
  readonly status: number;
  readonly problem: P;
  /** Seconds to wait before trying again (`Retry-After`, e.g. on a throttled login); absent if not sent. */
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, problem: P, retryAfterSeconds?: number) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    this.name = "ApiProblemError";
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** `Retry-After` as whole seconds from now: either delay-seconds or an HTTP date (RFC 9110, 10.2.3). */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | undefined {
  if (value === null || value.trim() === "") return undefined;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  const date = Date.parse(trimmed);
  return Number.isNaN(date) ? undefined : Math.max(0, Math.ceil((date - now) / 1000));
}

/** The error type of every generated hook (orval picks up this export from the mutator). */
export type ErrorType<P> = ApiProblemError<P extends ProblemDetail ? P : ProblemDetail>;

async function readProblem(response: Response): Promise<ProblemDetail> {
  const body: unknown = isJson(response) ? await response.json().catch(() => undefined) : undefined;
  return typeof body === "object" && body !== null ? (body as ProblemDetail) : { status: response.status };
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

/** The CSRF token the backend keeps in the `XSRF-TOKEN` cookie (Spring Security `csrf.spa()`). */
function csrfToken(): string | undefined {
  const cookie = document.cookie.split("; ").find((entry) => entry.startsWith("XSRF-TOKEN="));
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice("XSRF-TOKEN=".length));
}

/**
 * Echoes the CSRF cookie in the `X-XSRF-TOKEN` header of every unsafe request, as the backend
 * requires (ADR-0035). `GET /api/auth/session` hands out the cookie before the first login.
 */
function withCsrf(init: RequestInit | undefined): RequestInit | undefined {
  const method = (init?.method ?? "GET").toUpperCase();
  const token = csrfToken();
  if (SAFE_METHODS.has(method) || token === undefined) {
    return init;
  }
  const headers = new Headers(init?.headers);
  headers.set("X-XSRF-TOKEN", token);
  return { ...init, headers };
}

/**
 * The fetch every generated operation goes through (orval `mutator`). Paths are relative to the
 * app's origin: the backend serves the SPA and the API from the same host, so the session cookie
 * travels with every request.
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(new URL(path, window.location.origin), withCsrf(init));
  if (!response.ok) {
    const retryAfter = parseRetryAfter(response.headers.get("Retry-After"));
    throw new ApiProblemError(response.status, await readProblem(response), retryAfter);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  if (!isJson(response)) {
    // A file (e.g. a backup zip): the generated function types it as Blob. A File is a Blob that also
    // keeps the server's file name from Content-Disposition.
    return (await fileOf(response)) as T;
  }
  return (await response.json()) as T;
}

function isJson(response: Response): boolean {
  return response.headers.get("content-type")?.includes("json") ?? false;
}

async function fileOf(response: Response): Promise<Blob> {
  const blob = await response.blob();
  const name = attachmentName(response.headers.get("Content-Disposition"));
  return name === undefined ? blob : new File([blob], name, { type: blob.type });
}

/**
 * The file name of a `Content-Disposition` header (RFC 6266): `filename*=UTF-8''…` wins over
 * `filename=`; only the last path segment is kept, so a name can never point into a directory.
 */
export function attachmentName(header: string | null): string | undefined {
  if (header === null) return undefined;
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)?.[1];
  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/i.exec(header);
  let name: string | undefined;
  try {
    name = extended === undefined ? undefined : decodeURIComponent(extended.trim());
  } catch {
    name = undefined;
  }
  name ??= (plain?.[1] ?? plain?.[2])?.trim();
  const base = name?.split(/[/\\]/).pop()?.trim();
  return base === undefined || base === "" || base === "." || base === ".." ? undefined : base;
}
