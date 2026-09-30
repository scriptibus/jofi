// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ApplicationResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import type { SourceKind } from "./applicationsSearch";
import { languageName } from "./format";

// The list's own words and formats; statuses and dates come from `labels.ts` and `format.ts`.

/** Short source names for a table cell and the source filter (the detail page says more). */
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
export function languageLabel(tag: string): string {
  return languageName(tag) ?? tag;
}

/** A bare score 0–5 with one decimal ("4.5", "4,5"), for the compact "Want / Fit" cell. */
export function formatBareScore(score: number, locale: string = getLocale()): string {
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(score);
}
