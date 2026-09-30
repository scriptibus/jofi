// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ApplicationResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import type { SourceKind, Status } from "./applicationsSearch";

// The words and formats of the application list, in the user's language.

export const statusLabels: Record<Status, () => string> = {
  DISCOVERED: m.application_status_discovered,
  SHORTLISTED: m.application_status_shortlisted,
  PREPARING: m.application_status_preparing,
  APPLIED: m.application_status_applied,
  INTERVIEWING: m.application_status_interviewing,
  OFFER: m.application_status_offer,
  ACCEPTED: m.application_status_accepted,
  REJECTED: m.application_status_rejected,
  WITHDRAWN: m.application_status_withdrawn,
  DECLINED: m.application_status_declined,
  GHOSTED: m.application_status_ghosted,
};

export const sourceLabels: Record<SourceKind, () => string> = {
  SCANNER: m.applications_source_scanner,
  URL: m.applications_source_url,
  MANUAL_CHAT: m.applications_source_chat,
};

/** Where an application came from: its kinds of source, or "by hand" without any. */
export function sourceText(application: ApplicationResponse): string {
  const kinds = [...new Set(application.sources.map((source) => source.kind))];
  return kinds.length === 0
    ? m.applications_source_manual()
    : kinds.map((kind) => sourceLabels[kind]()).join(", ");
}

/** The language the user applies in: the chosen one, else the posting's (as the server filters). */
export function applicationLanguage(application: ApplicationResponse): string | undefined {
  const { applicationLanguage, postingLanguage } = application.languageAndTone;
  return applicationLanguage ?? postingLanguage ?? undefined;
}

/** A language tag's name in the user's language ("German"), or the tag when Intl does not know it. */
export function languageName(tag: string, locale: string = getLocale()): string {
  try {
    return new Intl.DisplayNames([locale], { type: "language", fallback: "none" }).of(tag) ?? tag;
  } catch {
    return tag;
  }
}

/** A calendar date from the API (`2026-10-15`, no time zone), the same in every time zone. */
export function formatDate(isoDate: string, locale: string = getLocale()): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined) return isoDate;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(Date.UTC(year, month - 1, day)),
  );
}

/** An instant from the API as a date in the user's time zone. */
export function formatInstantDate(instant: string, locale: string = getLocale()): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(instant));
}

/** A score 0–5 with one decimal ("4.5", "4,5"). */
export function formatScore(score: number, locale: string = getLocale()): string {
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(score);
}
