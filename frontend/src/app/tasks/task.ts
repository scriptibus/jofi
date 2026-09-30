// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {
  TaskGroupResponseGroup,
  TaskLinkDtoType,
  TaskRequest,
  TaskResponse,
  TaskTimingRequestBucket,
  TaskTimingResponse,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { fieldErrorsOf, VIOLATION_MESSAGES } from "../companies/company";

export type TaskBucket = NonNullable<TaskTimingRequestBucket>;
export type TaskGroup = TaskGroupResponseGroup;
export type TaskLinkType = TaskLinkDtoType;

/** Operation of the task delete's confirmation (backend `Task.DELETE_OPERATION`, ADR-0039). */
export const DELETE_OPERATION = "tasks.delete";

/** The server's limits (backend `TaskDetails`). */
export const MAX_TITLE_LENGTH = 300;
export const MAX_NOTES_LENGTH = 10_000;

/** Where a new task goes unless the user picks another bucket. */
export const DEFAULT_BUCKET: TaskBucket = "THIS_WEEK";

export const BUCKETS: readonly TaskBucket[] = ["TODAY", "THIS_WEEK", "NEXT_WEEK", "THIS_MONTH", "SOMEDAY"];

export const bucketLabels: Record<TaskBucket, () => string> = {
  TODAY: m.task_bucket_today,
  THIS_WEEK: m.task_bucket_this_week,
  NEXT_WEEK: m.task_bucket_next_week,
  THIS_MONTH: m.task_bucket_this_month,
  SOMEDAY: m.task_bucket_someday,
};

export const groupLabels: Record<TaskGroup, () => string> = {
  OVERDUE: m.task_group_overdue,
  TODAY: m.task_group_today,
  THIS_WEEK: m.task_group_this_week,
  NEXT_WEEK: m.task_group_next_week,
  THIS_MONTH: m.task_group_this_month,
  LATER: m.task_group_later,
  SOMEDAY: m.task_group_someday,
};

export const LINK_TYPES: readonly TaskLinkType[] = ["APPLICATION", "COMPANY", "CONTACT"];

export const linkTypeLabels: Record<TaskLinkType, () => string> = {
  APPLICATION: m.task_link_application,
  COMPANY: m.task_link_company,
  CONTACT: m.task_link_contact,
};

/** The browser's IANA zone (e.g. `Europe/Berlin`): buckets and the grouping follow the viewer's calendar. */
export function viewerTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** Today in `zone` as an ISO date (`2026-09-30`). */
export function todayIn(zone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  })
    .format(now)
    .slice(0, 10);
}

const DAY_MS = 86_400_000;

function addDays(isoDate: string, days: number): string {
  return new Date(Date.parse(`${isoDate}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

function mondayOf(isoDate: string): string {
  const weekday = new Date(`${isoDate}T00:00:00Z`).getUTCDay();
  return addDays(isoDate, -((weekday + 6) % 7));
}

/**
 * The bucket a task's rough timing still means on `today` (for the edit form), or undefined when none does
 * any more: an overdue bucket, or an exact time. The server resolves buckets again on every save.
 */
export function currentBucket(timing: TaskTimingResponse, today: string): TaskBucket | undefined {
  const { span, startsOn } = timing;
  if (span === "SOMEDAY") return "SOMEDAY";
  if (span === "DAY" && startsOn === today) return "TODAY";
  if (span === "WEEK" && startsOn === mondayOf(today)) return "THIS_WEEK";
  if (span === "WEEK" && startsOn === addDays(mondayOf(today), 7)) return "NEXT_WEEK";
  if (span === "MONTH" && startsOn === `${today.slice(0, 7)}-01`) return "THIS_MONTH";
  return undefined;
}

/** How the form's "when" is set: a bucket, an exact time, or not yet (an overdue bucket being edited). */
export type TimingChoice = TaskBucket | "EXACT" | "";

/** The task form's state: plain strings as typed, `""` for "not set". */
export interface TaskFormValues {
  title: string;
  notes: string;
  timing: TimingChoice;
  /** `datetime-local` value, e.g. `2026-10-05T10:00`. */
  localDue: string;
  /** The zone an exact time is on: the task's own when editing one, else the viewer's. */
  timeZone: string;
  linkType: TaskLinkType | "";
  linkId: string;
}

export function formValues(task?: TaskResponse, zone: string = viewerTimeZone()): TaskFormValues {
  const exact = task?.timing.localDue ? task.timing : undefined;
  const timing: TimingChoice = task
    ? exact
      ? "EXACT"
      : (currentBucket(task.timing, todayIn(zone)) ?? "")
    : DEFAULT_BUCKET;
  return {
    title: task?.title ?? "",
    notes: task?.notes ?? "",
    timing,
    localDue: exact?.localDue?.slice(0, 16) ?? "",
    timeZone: exact?.timeZone ?? zone,
    linkType: task?.link?.type ?? "",
    linkId: task?.link?.id ?? "",
  };
}

/** The full details for create and update: PUT replaces everything, so every field is sent. */
export function toTaskRequest(values: TaskFormValues): TaskRequest {
  const notes = values.notes.trim();
  const { timing } = values;
  return {
    title: values.title.trim(),
    notes: notes === "" ? null : notes,
    timing:
      timing === "EXACT"
        ? { timeZone: values.timeZone, localDue: values.localDue }
        : { timeZone: viewerTimeZone(), bucket: timing === "" ? null : timing },
    link:
      values.linkType !== "" && values.linkId !== "" ? { type: values.linkType, id: values.linkId } : null,
  };
}

/** Form field names, matching the server's violation names. */
export const FIELD_NAMES = {
  title: "title",
  notes: "notes",
  timing: "timing",
  localDue: "timing.localDue",
  timeZone: "timing.timeZone",
  link: "link.id",
} as const;

const taskViolationMessages: Readonly<Record<string, () => string>> = {
  ...VIOLATION_MESSAGES,
  OUT_OF_RANGE: m.task_violation_out_of_range,
  INVALID_TIME_ZONE: m.task_violation_time_zone,
  AMBIGUOUS: m.task_violation_timing,
  NOT_FOUND: m.task_violation_link_not_found,
};

/** A 400's violations by the request's field names (`timing.localDue`); undefined otherwise. */
export function taskFieldErrorsOf(error: unknown): Record<string, string> | undefined {
  return fieldErrorsOf(error, taskViolationMessages);
}

function dateOnly(isoDate: string, options: Intl.DateTimeFormatOptions, locale: string): string {
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" }).format(
    new Date(`${isoDate}T00:00:00Z`),
  );
}

function dateTime(instant: Date, zone: string, locale: string): string {
  const options: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "short" };
  try {
    return new Intl.DateTimeFormat(locale, { ...options, timeZone: zone }).format(instant);
  } catch {
    // A zone this browser does not know must not break the list: show the browser's own time.
    return new Intl.DateTimeFormat(locale, options).format(instant);
  }
}

/**
 * When a task is due, in words: "Due Oct 5, 2026, 10:00 AM" in the viewer's zone (plus the agreed time when it
 * was planned in another zone), "Due Sep 30, 2026", "Week of Sep 28, 2026", "October 2026" or "Someday".
 */
export function describeTiming(
  timing: TaskTimingResponse,
  viewerZone: string = viewerTimeZone(),
  locale: string = getLocale(),
): string {
  if (timing.dueAt) {
    const due = new Date(timing.dueAt);
    const when = m.task_timing_exact({ when: dateTime(due, viewerZone, locale) });
    const zone = timing.timeZone;
    if (!zone || zone === viewerZone) return when;
    return m.task_timing_other_zone({ when, local: dateTime(due, zone, locale), zone });
  }
  const start = timing.startsOn;
  if (!start || timing.span === "SOMEDAY") return m.task_bucket_someday();
  if (timing.span === "WEEK")
    return m.task_timing_week({ date: dateOnly(start, { dateStyle: "medium" }, locale) });
  if (timing.span === "MONTH") return dateOnly(start, { month: "long", year: "numeric" }, locale);
  return m.task_timing_day({ date: dateOnly(start, { dateStyle: "medium" }, locale) });
}
