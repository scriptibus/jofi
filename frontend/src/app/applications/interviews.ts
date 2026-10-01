// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import type { ConfirmationEffect } from "../../api/confirmation";
import {
  getGetApplicationTimelineQueryKey,
  getListInterviewsQueryKey,
  getListUpcomingInterviewsQueryKey,
  type InterviewRequest,
  type InterviewResponse,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { fieldErrorsOf, VIOLATION_MESSAGES } from "../companies/company";
import { type ErrorDescription, isProblem } from "../problems";
import { describeApplicationError } from "./applicationProblems";
import { formatInstant, formatLocalDateTime } from "./format";
import { type InterviewOutcome, type InterviewType, interviewTypeLabels } from "./labels";

/** Operation of the interview delete's two-step confirmation (backend `Interview.DELETE_OPERATION`). */
export const DELETE_INTERVIEW_OPERATION = "interviews.delete";

/** The most participants one interview has (backend `InterviewDetails.MAX_PARTICIPANTS`). */
export const MAX_PARTICIPANTS = 20;

const INTERVIEW_NOT_FOUND = "urn:jofi:problem:applications:interview-not-found";

export interface InterviewFormValues {
  type: InterviewType;
  /** The agreed wall-clock time (`2026-10-05T10:00`), null until the user enters it. */
  localStart: string | null;
  /** The zone the time was agreed in (ADR-0048): an IANA id such as `Europe/Berlin`. */
  timeZone: string;
  participantIds: string[];
  preparationNotes: string;
  notes: string;
  outcome: InterviewOutcome | null;
}

/** The zone of the user's device, the default for a new interview. */
export function deviceTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
}

/** The form's start values: an interview's details as stored, or a new phone screen in the device's zone. */
export function interviewFormValues(
  interview?: InterviewResponse,
  zone: string = deviceTimeZone(),
): InterviewFormValues {
  if (!interview)
    return {
      type: "PHONE_SCREEN",
      localStart: null,
      timeZone: zone,
      participantIds: [],
      preparationNotes: "",
      notes: "",
      outcome: null,
    };
  return {
    type: interview.type,
    localStart: interview.localStart,
    timeZone: interview.timeZone,
    participantIds: [...interview.participantIds],
    preparationNotes: interview.preparationNotes ?? "",
    notes: interview.notes ?? "",
    outcome: interview.outcome ?? null,
  };
}

/** The request for log and edit: every field, since an edit replaces them all; blank notes are absent. */
export function toInterviewRequest(values: InterviewFormValues & { localStart: string }): InterviewRequest {
  const text = (value: string) => (value.trim() === "" ? null : value);
  return {
    type: values.type,
    localStart: values.localStart,
    timeZone: values.timeZone,
    participantIds: values.participantIds,
    preparationNotes: text(values.preparationNotes),
    notes: text(values.notes),
    outcome: values.outcome,
  };
}

/** The zones to choose from: every IANA zone the browser knows, UTC, and `current` if it is none of them. */
export function timeZoneChoices(current: string): string[] {
  const zones = new Set(Intl.supportedValuesOf("timeZone"));
  zones.add("UTC");
  zones.add(current);
  return [...zones].sort((a, b) => a.localeCompare(b));
}

/** "Oct 5, 2026, 10:00 AM (Europe/Berlin)": the agreed time in the zone it was agreed in. */
export function formatAgreedTime(localStart: string, timeZone: string, locale: string = getLocale()): string {
  return m.application_timeline_interview_time({
    time: formatLocalDateTime(localStart, locale),
    zone: timeZone,
  });
}

/**
 * The start on the user's own clock when that reads differently from the agreed time (another zone, or the
 * same name with other rules), else undefined.
 */
export function timeOnUserClock(
  interview: Pick<InterviewResponse, "startsAt" | "localStart">,
  locale: string = getLocale(),
): string | undefined {
  const mine = formatInstant(interview.startsAt, locale);
  return mine === formatLocalDateTime(interview.localStart, locale) ? undefined : mine;
}

/**
 * The delete question from the server's effect, whose name is `<TYPE> <localStart> <zone>` (backend
 * `DeleteInterviewUseCase`), e.g. "Delete the phone screen on Oct 6, 2026, 2:30 PM (Europe/Berlin)?".
 */
export function describeInterviewDelete(effect: ConfirmationEffect): string {
  const [type = "", localStart = "", ...zone] = effect.name.split(" ");
  const label = Object.hasOwn(interviewTypeLabels, type)
    ? interviewTypeLabels[type as InterviewType]
    : undefined;
  if (!label || !localStart || zone.length === 0)
    return m.application_interview_delete_confirm_raw({ name: effect.name });
  return m.application_interview_delete_confirm({
    type: label(),
    time: formatAgreedTime(localStart, zone.join(" ")),
  });
}

export function isInterviewNotFound(error: unknown): boolean {
  return isProblem(error, INTERVIEW_NOT_FOUND);
}

/** A failed interview call in the user's language. */
export function describeInterviewError(error: unknown): ErrorDescription {
  if (isInterviewNotFound(error)) return { message: m.application_interview_error_not_found() };
  return describeApplicationError(error);
}

const interviewViolations: Readonly<Record<string, () => string>> = {
  ...VIOLATION_MESSAGES,
  INVALID_TIME_ZONE: m.application_interview_error_time_zone,
  OUT_OF_RANGE: m.application_interview_error_start_range,
  NOT_FOUND: m.application_interview_error_participant_gone,
};

/** A 400's violations by request field (`localStart`, `timeZone`, `participantIds`, `notes`, …). */
export function interviewFieldErrors(error: unknown): Record<string, string> | undefined {
  return fieldErrorsOf(error, interviewViolations);
}

/** After a log, edit or delete: the list, the timeline and the upcoming interviews read anew. */
export function refreshInterviews(queryClient: QueryClient, applicationId: string): Promise<unknown> {
  return Promise.all(
    [
      getListInterviewsQueryKey(applicationId),
      getGetApplicationTimelineQueryKey(applicationId),
      getListUpcomingInterviewsQueryKey(),
    ].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
  );
}
