// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { m } from "../../paraglide/messages.js";
import { CompanyProblemType } from "../companies/companyProblems";
import { describeError, type ErrorDescription, isProblem } from "../problems";

const COMPANIES = "urn:jofi:problem:companies:";

/** Problem types of the contact endpoints (backend `ContactProblems`, ADR-0041). */
export const ContactProblemType = {
  notFound: `${COMPANIES}contact-not-found`,
  versionConflict: `${COMPANIES}contact-version-conflict`,
} as const;

/** Someone saved the contact after this page read it (another tab, the AI, an MCP client). */
export function isContactVersionConflict(error: unknown): boolean {
  return isProblem(error, ContactProblemType.versionConflict);
}

export function isContactNotFound(error: unknown): boolean {
  return isProblem(error, ContactProblemType.notFound);
}

/** A failed contact call in the user's language; the rest goes to `describeError`. */
export function describeContactError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.contact_error_mismatch() };
  if (isContactVersionConflict(error)) return { message: m.contact_error_version_conflict() };
  if (isContactNotFound(error)) return { message: m.contact_error_not_found() };
  if (isProblem(error, CompanyProblemType.unavailable)) return { message: m.error_unavailable() };
  return describeError(error);
}
