// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiProblemError } from "./fetcher";
import type { ConfirmationRequiredProblem } from "./generated/jofi";

/** Request header that carries the token of the second step (backend `Confirmations.HEADER`, ADR-0039). */
export const CONFIRMATION_HEADER = "Jofi-Confirmation";

/** Problem type of the 428 answer: nothing ran yet, the user has to confirm. */
export const CONFIRMATION_REQUIRED = "urn:jofi:problem:shared:confirmation-required";

/** Problem type of the 412 answer: the token was unknown, used, expired or for another request. */
export const CONFIRMATION_INVALID = "urn:jofi:problem:shared:confirmation-invalid";

/** The confirmation request inside a 428 answer, or undefined for any other error. */
export function confirmationRequest(error: unknown): ConfirmationRequiredProblem | undefined {
  if (!(error instanceof ApiProblemError) || error.status !== 428) return undefined;
  const problem = error.problem as Partial<ConfirmationRequiredProblem>;
  if (problem.type !== CONFIRMATION_REQUIRED || typeof problem.confirmationToken !== "string")
    return undefined;
  return problem as ConfirmationRequiredProblem;
}

export type ConfirmedOutcome<T> = { status: "done"; value: T } | { status: "cancelled" };

/**
 * Runs a destructive or outward-facing call with the server's two-step confirmation. `call` gets
 * the extra request options to pass to the generated request function. The first call runs without
 * a token; if the server answers 428, `ask` shows the user what would happen, and only on a yes is
 * the call repeated with the token. Any other error (including a 412 for a stale token) is thrown.
 */
export async function runConfirmed<T>(
  call: (options?: RequestInit) => Promise<T>,
  ask: (request: ConfirmationRequiredProblem) => Promise<boolean>,
): Promise<ConfirmedOutcome<T>> {
  try {
    return { status: "done", value: await call() };
  } catch (error) {
    const request = confirmationRequest(error);
    if (request === undefined) throw error;
    if (!(await ask(request))) return { status: "cancelled" };
    const value = await call({ headers: { [CONFIRMATION_HEADER]: request.confirmationToken } });
    return { status: "done", value };
  }
}
