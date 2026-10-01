// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import type { DashboardCountdownResponse, DashboardCountdownResponseSource } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { formatDate, formatLocalDateTime } from "../applications/format";
import { describeError, type ErrorDescription, isProblem } from "../problems";
import { todayIn } from "../tasks/task";

export type CountdownSource = DashboardCountdownResponseSource;

/** Operation of the countdown delete's confirmation (backend `Countdown.DELETE_OPERATION`, ADR-0039). */
export const DELETE_OPERATION = "countdowns.delete";

/** The server's limit (backend `CountdownDetails.MAX_TITLE_LENGTH`). */
export const MAX_TITLE_LENGTH = 200;

/** Problem type of a countdown that is gone (backend `TaskProblems.COUNTDOWN_NOT_FOUND`). */
const COUNTDOWN_NOT_FOUND = "urn:jofi:problem:tasks:countdown-not-found";

export const sourceLabels: Record<CountdownSource, () => string> = {
  CUSTOM: m.countdown_source_custom,
  NEXT_INTERVIEW: m.countdown_source_next_interview,
  APPLICATION_DEADLINE: m.countdown_source_application_deadline,
  OFFER_ANSWER_DEADLINE: m.countdown_source_offer_answer_deadline,
};

const DAY_MS = 86_400_000;

/** Whole days from `from` to `to` (ISO dates); negative when `to` lies before `from`. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS);
}

/**
 * The day a countdown ends on the viewer's calendar (backend `CountdownTarget.endsAt`): its date, or the day its
 * instant falls on in the viewer's zone.
 */
export function targetDay(countdown: DashboardCountdownResponse, viewerZone: string): string | undefined {
  if (countdown.targetDate) return countdown.targetDate;
  if (countdown.targetAt) return todayIn(viewerZone, new Date(countdown.targetAt));
  return undefined;
}

/** What is left, in words: "Today", "Tomorrow", "In 12 days" or, for a day that has passed, "Reached". */
export function describeRemaining(days: number): string {
  if (days < 0) return m.countdown_reached();
  if (days === 0) return m.countdown_today();
  if (days === 1) return m.countdown_tomorrow();
  return m.countdown_in_days({ count: days });
}

const DATE_TIME: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "short" };

function dateTimeIn(instant: Date, zone: string, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { ...DATE_TIME, timeZone: zone }).format(instant);
  } catch {
    // A zone this browser does not know must not break the widget: show the browser's own time.
    return new Intl.DateTimeFormat(locale, DATE_TIME).format(instant);
  }
}

/**
 * When a countdown ends, locale-formatted: a date ("Oct 31, 2026"), or a time in the viewer's zone plus the
 * agreed wall-clock time when it was planned in another zone ("Oct 5, 2026, 9:00 AM (10:00 AM in Europe/Berlin)").
 */
export function describeTarget(
  countdown: DashboardCountdownResponse,
  viewerZone: string,
  locale: string = getLocale(),
): string {
  if (countdown.targetDate) return formatDate(countdown.targetDate, locale);
  if (!countdown.targetAt) return "";
  const when = dateTimeIn(new Date(countdown.targetAt), viewerZone, locale);
  const zone = countdown.timeZone;
  if (!zone || zone === viewerZone || !countdown.localTarget) return when;
  return m.countdown_when_other_zone({
    when,
    local: formatLocalDateTime(countdown.localTarget, locale),
    zone,
  });
}

/** A failed countdown call in the user's language; the rest goes to `describeError`. */
export function describeCountdownError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.countdown_error_mismatch() };
  if (isProblem(error, COUNTDOWN_NOT_FOUND)) return { message: m.countdown_error_not_found() };
  return describeError(error);
}
