// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ActivityEntryResponse, StatusCountDto } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import type { Status } from "../applications/labels";

/** How many activity entries the dashboard shows (the server allows 1 to 100). */
export const ACTIVITY_LIMIT = 10;
/** How many tasks each list of the tasks widget shows before "and n more". */
export const TASKS_SHOWN = 5;

/**
 * The statuses that imply having applied (backend `ApplicationStatus.impliesApplied`, ADR-0052): the funnel's
 * "applied" stage, as a filter of the applications list.
 */
export const APPLIED_STATUSES: readonly Status[] = [
  "APPLIED",
  "INTERVIEWING",
  "OFFER",
  "ACCEPTED",
  "REJECTED",
  "WITHDRAWN",
  "GHOSTED",
];

/** A count in the user's locale: "1,234", "1.234". */
export function formatCount(count: number, locale: string = getLocale()): string {
  return new Intl.NumberFormat(locale).format(count);
}

/** A rate from 0 to 1 as a whole percentage in the user's locale ("40%", "40 %"); null while it has no base. */
export function formatRate(rate: number | null | undefined, locale: string = getLocale()): string | null {
  if (rate == null) return null;
  return new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 0 }).format(rate);
}

/** The statuses that hold applications, in pipeline order (the server sends all of them, zeros included). */
export function occupiedStatuses(byStatus: readonly StatusCountDto[]): StatusCountDto[] {
  return byStatus.filter(({ count }) => count > 0);
}

// Recent activity (ADR-0052): the server describes each entry with a fixed English text the code writes, such as
// "Created application" or "Edited task; also changed: title". The dashboard shows what kind of record it was and
// what happened to it in the user's language, read from the entity type and the description's first word.

const entityLabels: Record<string, () => string> = {
  application: m.dashboard_entity_application,
  application_source: m.dashboard_entity_source,
  description_snapshot: m.dashboard_entity_description,
  interview: m.dashboard_entity_interview,
  company: m.dashboard_entity_company,
  contact: m.dashboard_entity_contact,
  task: m.dashboard_entity_task,
  countdown: m.dashboard_entity_countdown,
};

type Happening = (inputs: { entity: string }) => string;

const happenings: Record<string, Happening> = {
  Created: m.dashboard_activity_created,
  Added: m.dashboard_activity_added,
  Recorded: m.dashboard_activity_added,
  Imported: m.dashboard_activity_imported,
  Deleted: m.dashboard_activity_deleted,
  Completed: m.dashboard_activity_completed,
  Reopened: m.dashboard_activity_reopened,
  Suggested: m.dashboard_activity_suggested,
  Accepted: m.dashboard_activity_accepted,
  Dismissed: m.dashboard_activity_dismissed,
  Froze: m.dashboard_activity_frozen,
  Cancelled: m.dashboard_activity_cancelled,
};

/** What an activity entry says happened, e.g. "Application created", "Status changed", "Suggestion dismissed". */
export function describeActivity({ entityType, description }: ActivityEntryResponse): string {
  if (entityType === "application" && description.startsWith("Changed application status"))
    return m.dashboard_activity_status_changed();
  const suggestion = entityType === "task" && /\bsuggestion\b/.test(description);
  const entity = suggestion
    ? m.dashboard_entity_suggestion()
    : (entityLabels[entityType]?.() ?? m.dashboard_entity_other());
  const verb = description.split(/[\s;]/, 1)[0] ?? "";
  return (happenings[verb] ?? m.dashboard_activity_changed)({ entity });
}
