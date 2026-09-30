// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription, isProblem } from "../problems";

const COMPANIES = "urn:jofi:problem:companies:";

/** Problem types of the company endpoints (backend `CompanyProblems`, ADR-0041). */
export const CompanyProblemType = {
  notFound: `${COMPANIES}company-not-found`,
  versionConflict: `${COMPANIES}version-conflict`,
  hasApplications: `${COMPANIES}has-applications`,
  unavailable: `${COMPANIES}storage-unavailable`,
} as const;

/** Someone saved the company after this page read it (another tab, the AI, an MCP client). */
export function isVersionConflict(error: unknown): boolean {
  return isProblem(error, CompanyProblemType.versionConflict);
}

export function isNotFound(error: unknown): boolean {
  return isProblem(error, CompanyProblemType.notFound);
}

/** A failed company call in the user's language; the rest goes to `describeError`. */
export function describeCompanyError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.company_error_mismatch() };
  if (isVersionConflict(error)) return { message: m.company_error_version_conflict() };
  if (isProblem(error, CompanyProblemType.hasApplications)) return { message: m.company_error_has_applications() };
  if (isNotFound(error)) return { message: m.company_error_not_found() };
  if (isProblem(error, CompanyProblemType.unavailable)) return { message: m.error_unavailable() };
  return describeError(error);
}
