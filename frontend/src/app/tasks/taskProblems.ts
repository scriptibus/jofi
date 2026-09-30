// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription, isProblem } from "../problems";

const TASKS = "urn:jofi:problem:tasks:";

/** Problem types of the task endpoints (backend `TaskProblems`, ADR-0041). */
export const TaskProblemType = {
  notFound: `${TASKS}task-not-found`,
  versionConflict: `${TASKS}version-conflict`,
  invalidTransition: `${TASKS}invalid-transition`,
  unavailable: `${TASKS}storage-unavailable`,
} as const;

/** Someone changed the task after this page read it (another tab, the AI, an MCP client). */
export function isTaskVersionConflict(error: unknown): boolean {
  return isProblem(error, TaskProblemType.versionConflict);
}

export function isTaskNotFound(error: unknown): boolean {
  return isProblem(error, TaskProblemType.notFound);
}

/** A failed task call in the user's language; the rest goes to `describeError`. */
export function describeTaskError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.task_error_mismatch() };
  if (isTaskVersionConflict(error) || isProblem(error, TaskProblemType.invalidTransition))
    return { message: m.task_error_version_conflict() };
  if (isTaskNotFound(error)) return { message: m.task_error_not_found() };
  if (isProblem(error, TaskProblemType.unavailable)) return { message: m.error_unavailable() };
  return describeError(error);
}
