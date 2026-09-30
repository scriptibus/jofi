// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription, isProblem } from "../problems";

const APPLICATIONS = "urn:jofi:problem:applications:";

/** Problem types of the application endpoints (backend `ApplicationProblems`, ADR-0041). */
export const ApplicationProblemType = {
  notFound: `${APPLICATIONS}application-not-found`,
  versionConflict: `${APPLICATIONS}version-conflict`,
  unavailable: `${APPLICATIONS}storage-unavailable`,
} as const;

/** Someone saved the application after this page read it (another tab, the AI, a scanner, an MCP client). */
export function isApplicationVersionConflict(error: unknown): boolean {
  return isProblem(error, ApplicationProblemType.versionConflict);
}

export function isApplicationNotFound(error: unknown): boolean {
  return isProblem(error, ApplicationProblemType.notFound);
}

/** A failed application call in the user's language; the rest goes to `describeError`. */
export function describeApplicationError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.application_error_mismatch() };
  if (isApplicationVersionConflict(error)) return { message: m.application_error_version_conflict() };
  if (isApplicationNotFound(error)) return { message: m.application_error_not_found() };
  if (isProblem(error, ApplicationProblemType.unavailable)) return { message: m.error_unavailable() };
  return describeError(error);
}
