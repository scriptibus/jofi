// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription } from "../problems";

const SETUP = "urn:jofi:problem:setup:";

/** Problem types of the AI setup endpoints (backend `SetupProblems`). */
export const SetupProblemType = {
  invalid: `${SETUP}invalid-input`,
  notFound: `${SETUP}provider-not-found`,
  inUse: `${SETUP}provider-in-use`,
  forbidden: `${SETUP}forbidden`,
  authenticationFailed: `${SETUP}provider-authentication-failed`,
  rateLimited: `${SETUP}provider-rate-limited`,
  unreachable: `${SETUP}provider-unreachable`,
  rejected: `${SETUP}provider-rejected`,
  storageUnavailable: `${SETUP}storage-unavailable`,
} as const;

const knownMessages: Record<string, () => string> = {
  [SetupProblemType.notFound]: m.ai_error_not_found,
  [SetupProblemType.inUse]: m.ai_error_in_use,
  [SetupProblemType.forbidden]: m.ai_error_forbidden,
  [SetupProblemType.authenticationFailed]: m.ai_error_authentication_failed,
  [SetupProblemType.rateLimited]: m.ai_error_rate_limited,
  [SetupProblemType.unreachable]: m.ai_error_unreachable,
  [SetupProblemType.rejected]: m.ai_error_rejected,
  [SetupProblemType.storageUnavailable]: m.ai_error_storage,
};

/** A failed setup call in the user's language; field violations are shown at their fields instead. */
export function describeSetupError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.ai_error_mismatch() };
  const type = error instanceof ApiProblemError ? error.problem.type : undefined;
  const known = type === undefined ? undefined : knownMessages[type];
  if (known) return { message: known() };
  if (type === SetupProblemType.invalid) return { message: m.ai_error_invalid() };
  return describeError(error);
}

export interface Violation {
  field: string;
  problem: string;
}

/** The `violations` of a 400 `invalid-input` answer, or none. */
export function violationsOf(error: unknown): Violation[] {
  if (!(error instanceof ApiProblemError) || error.problem.type !== SetupProblemType.invalid) return [];
  const violations: unknown = error.problem.violations;
  if (!Array.isArray(violations)) return [];
  return violations.filter(
    (entry): entry is Violation =>
      typeof entry === "object" &&
      entry !== null &&
      typeof (entry as Violation).field === "string" &&
      typeof (entry as Violation).problem === "string",
  );
}

const problemMessages: Record<string, () => string> = {
  REQUIRED: m.ai_violation_required,
  TOO_LONG: m.ai_violation_too_long,
  INVALID_URL: m.ai_base_url_invalid,
  NOT_ALLOWED: m.ai_violation_not_allowed,
  OUT_OF_RANGE: m.ai_violation_out_of_range,
  INVALID_FORMAT: m.ai_violation_invalid_format,
};

/**
 * Violations as `{ field: message }` for a form's `validationErrors`. A missing key on an update means
 * the base URL moved to another server, which needs the key again (backend `keyMustBeReenteredFor`).
 */
export function fieldErrorsOf(error: unknown, updating = false): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const { field, problem } of violationsOf(error)) {
    if (field === "apiKey" && problem === "REQUIRED") {
      errors[field] = updating ? m.ai_key_reenter() : m.ai_key_required();
      continue;
    }
    const message = Object.hasOwn(problemMessages, problem) ? problemMessages[problem] : undefined;
    errors[field] = message ? message() : m.ai_violation_other({ problem });
  }
  return errors;
}
