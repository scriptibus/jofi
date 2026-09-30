// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiProblemError } from "./fetcher";
import type { ConfirmationRequiredProblem, ConfirmationRequiredProblemEffect } from "./generated/jofi";

/** Request header that carries the token of the second step (backend `Confirmations.HEADER`, ADR-0039). */
export const CONFIRMATION_HEADER = "Jofi-Confirmation";

/** Problem type of the 428 answer: nothing ran yet, the user has to confirm. */
export const CONFIRMATION_REQUIRED = "urn:jofi:problem:shared:confirmation-required";

/** Problem type of the 412 answer: the token was unknown, used, expired or for another request. */
export const CONFIRMATION_INVALID = "urn:jofi:problem:shared:confirmation-invalid";

/** What the server says would change; render it with the feature's own Paraglide message. */
export type ConfirmationEffect = ConfirmationRequiredProblemEffect;

/** What the caller means to run: the dialog is only shown if the server's request matches it. */
export interface ExpectedAction {
  operation: string;
  targets: readonly string[];
}

/** The server asked to confirm something other than what the caller meant to run; nothing was asked or run. */
export class ConfirmationMismatchError extends Error {
  readonly expected: ExpectedAction;
  readonly requested: ExpectedAction;

  constructor(expected: ExpectedAction, requested: ExpectedAction) {
    super(`Confirmation for ${requested.operation} does not match the expected ${expected.operation}`);
    this.name = "ConfirmationMismatchError";
    this.expected = expected;
    this.requested = requested;
  }
}

/** The confirmation request inside a 428 answer, or undefined for any other error. */
export function confirmationRequest(error: unknown): ConfirmationRequiredProblem | undefined {
  if (!(error instanceof ApiProblemError) || error.status !== 428) return undefined;
  const problem = error.problem as Partial<ConfirmationRequiredProblem>;
  const complete =
    problem.type === CONFIRMATION_REQUIRED &&
    typeof problem.confirmationToken === "string" &&
    typeof problem.operation === "string" &&
    Array.isArray(problem.targets) &&
    typeof problem.effect === "object" &&
    problem.effect !== null;
  return complete ? (problem as ConfirmationRequiredProblem) : undefined;
}

/** Same operation and the same set of targets (the server sorts and de-duplicates them). */
export function matchesExpectation(request: ExpectedAction, expected: ExpectedAction): boolean {
  const normalise = (targets: readonly string[]) => [...new Set(targets)].sort();
  const requested = normalise(request.targets);
  const wanted = normalise(expected.targets);
  return (
    request.operation === expected.operation &&
    requested.length === wanted.length &&
    requested.every((target, index) => target === wanted[index])
  );
}

export type ConfirmedOutcome<T> = { status: "done"; value: T } | { status: "cancelled" };

/**
 * Runs a destructive or outward-facing call with the server's two-step confirmation. `call` gets
 * the extra request options to pass to the generated request function. The first call runs without
 * a token; if the server answers 428 for exactly the `expected` operation and targets, `ask` shows
 * the user the server's effect, and only on a yes is the call repeated with the token. A 428 for
 * anything else throws `ConfirmationMismatchError` without asking; any other error (including a 412
 * for a stale token) is thrown as is.
 */
export async function runConfirmed<T>(
  call: (options?: RequestInit) => Promise<T>,
  expected: ExpectedAction,
  ask: (request: ConfirmationRequiredProblem) => Promise<boolean>,
): Promise<ConfirmedOutcome<T>> {
  try {
    return { status: "done", value: await call() };
  } catch (error) {
    const request = confirmationRequest(error);
    if (request === undefined) throw error;
    if (!matchesExpectation(request, expected)) throw new ConfirmationMismatchError(expected, request);
    if (!(await ask(request))) return { status: "cancelled" };
    const value = await call({ headers: { [CONFIRMATION_HEADER]: request.confirmationToken } });
    return { status: "done", value };
  }
}
