// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiProblemError } from "../../api/fetcher";
import type {
  CompanyDetailsRequest,
  CompanyResponse,
  CompanyResponsePreference,
  CompanyResponseSize,
  ValidationProblem,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";

export type Preference = CompanyResponsePreference;
export type CompanySize = NonNullable<CompanyResponseSize>;

export const PREFERENCES: readonly Preference[] = ["NONE", "FAVOURITE", "BLACKLISTED"];
export const SIZES: readonly CompanySize[] = ["MICRO", "SMALL", "MEDIUM", "LARGE", "ENTERPRISE"];

/** Operation of the company delete's confirmation (backend `Company.DELETE_OPERATION`, ADR-0039). */
export const DELETE_OPERATION = "companies.delete";

export function preferenceLabel(preference: Preference): string {
  if (preference === "FAVOURITE") return m.company_preference_favourite();
  if (preference === "BLACKLISTED") return m.company_preference_blacklisted();
  return m.company_preference_none();
}

const sizeLabels: Record<CompanySize, () => string> = {
  MICRO: m.company_size_micro,
  SMALL: m.company_size_small,
  MEDIUM: m.company_size_medium,
  LARGE: m.company_size_large,
  ENTERPRISE: m.company_size_enterprise,
};

export function sizeLabel(size: CompanySize): string {
  return sizeLabels[size]();
}

/** The company form's state: plain strings as typed, `""` for "not set". */
export interface CompanyFormValues {
  name: string;
  website: string;
  careersPage: string;
  industry: string;
  size: CompanySize | "";
  /** One location per line, the main one first. */
  locations: string;
  researchNotes: string;
}

export function formValues(company?: CompanyResponse): CompanyFormValues {
  return {
    name: company?.name ?? "",
    website: company?.website ?? "",
    careersPage: company?.careersPage ?? "",
    industry: company?.industry ?? "",
    size: company?.size ?? "",
    locations: company?.locations.join("\n") ?? "",
    researchNotes: company?.researchNotes ?? "",
  };
}

/** Trimmed text, or null when empty: the server stores no blank or padded text (ADR-0041). */
function optional(text: string): string | null {
  const trimmed = text.trim();
  return trimmed === "" ? null : trimmed;
}

/** One location per non-empty line, trimmed, without case-insensitive repeats (the server refuses them). */
export function parseLocations(text: string): string[] {
  const seen = new Set<string>();
  const locations: string[] = [];
  for (const line of text.split("\n")) {
    const location = line.trim();
    if (location === "" || seen.has(location.toLowerCase())) continue;
    seen.add(location.toLowerCase());
    locations.push(location);
  }
  return locations;
}

/** The full details for create and update: PUT replaces everything, so every field is sent. */
export function toDetailsRequest(values: CompanyFormValues): CompanyDetailsRequest {
  return {
    name: values.name.trim(),
    website: optional(values.website),
    careersPage: optional(values.careersPage),
    industry: optional(values.industry),
    size: values.size === "" ? null : values.size,
    locations: parseLocations(values.locations),
    researchNotes: optional(values.researchNotes),
  };
}

/** Looks like an absolute http(s) URL with a host; the server has the final say. */
export function isWebAddress(text: string): boolean {
  return /^https?:\/\/[^\s/?#@]+(?:[/?#]\S*)?$/i.test(text.trim());
}

const violationMessages: Record<string, () => string> = {
  REQUIRED: m.company_violation_required,
  TOO_LONG: m.company_violation_too_long,
  TOO_MANY: m.company_violation_too_many,
  INVALID_URL: m.company_violation_invalid_url,
  INVALID_CHARACTER: m.company_violation_invalid_character,
};

/** A 400's field violations as form errors by field name, or undefined for any other error. */
export function fieldErrorsOf(error: unknown): Record<string, string> | undefined {
  if (!(error instanceof ApiProblemError) || error.status !== 400) return undefined;
  const violations = (error.problem as Partial<ValidationProblem>).violations;
  if (!Array.isArray(violations) || violations.length === 0) return undefined;
  const errors: Record<string, string> = {};
  for (const { field, problem } of violations) {
    const message = Object.hasOwn(violationMessages, problem) ? violationMessages[problem] : undefined;
    errors[field] = message ? message() : m.company_violation_other();
  }
  return errors;
}
