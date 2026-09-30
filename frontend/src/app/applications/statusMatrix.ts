// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { Status } from "./labels";

// The status transition matrix of ADR-0044 (backend `ApplicationStatus.canMoveTo`), mirrored so the status
// control only offers moves the server accepts. The server stays the judge: a move it refuses (409
// `invalid-transition`) still shows a message. Keep both in step; `statusMatrix.test.ts` holds the full table.

/** The pipeline, in order. */
export const PIPELINE: readonly Status[] = [
  "DISCOVERED",
  "SHORTLISTED",
  "PREPARING",
  "APPLIED",
  "INTERVIEWING",
  "OFFER",
];

/** The ended statuses, in the order the control lists them. */
export const TERMINAL: readonly Status[] = ["ACCEPTED", "REJECTED", "WITHDRAWN", "DECLINED", "GHOSTED"];

/** The statuses whose move needs a decline category (the reason text stays optional). */
export function takesDeclineReason(status: Status): boolean {
  return status === "DECLINED" || status === "REJECTED";
}

const otherPipeline = (status: Status) => PIPELINE.filter((other) => other !== status);

const SUCCESSORS: Record<Status, readonly Status[]> = {
  DISCOVERED: [...otherPipeline("DISCOVERED"), "DECLINED"],
  SHORTLISTED: [...otherPipeline("SHORTLISTED"), "DECLINED"],
  PREPARING: [...otherPipeline("PREPARING"), "DECLINED"],
  APPLIED: [...otherPipeline("APPLIED"), "REJECTED", "WITHDRAWN", "GHOSTED"],
  INTERVIEWING: [...otherPipeline("INTERVIEWING"), "REJECTED", "WITHDRAWN", "GHOSTED"],
  OFFER: [...otherPipeline("OFFER"), "ACCEPTED", "REJECTED", "DECLINED", "GHOSTED"],
  ACCEPTED: [...PIPELINE, "REJECTED", "DECLINED"],
  REJECTED: [...PIPELINE, "REJECTED"],
  WITHDRAWN: [...PIPELINE],
  DECLINED: [...PIPELINE, "DECLINED"],
  GHOSTED: [...PIPELINE, "REJECTED", "WITHDRAWN"],
};

/** Whether an application in `from` may move to `to`; `DECLINED → DECLINED` and `REJECTED → REJECTED` correct the reason. */
export function canMoveTo(from: Status, to: Status): boolean {
  return SUCCESSORS[from].includes(to);
}

/** The other statuses an application in `from` may move to (without the reason correction), pipeline first. */
export function nextStatuses(from: Status): { pipeline: Status[]; ended: Status[] } {
  const allowed = (to: Status) => to !== from && canMoveTo(from, to);
  return { pipeline: PIPELINE.filter(allowed), ended: TERMINAL.filter(allowed) };
}
