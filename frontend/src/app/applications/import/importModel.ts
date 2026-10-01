// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiProblemError } from "../../../api/fetcher";
import { type PostingImportResponse, PostingImportResponseFailure } from "../../../api/generated/jofi";
import { m } from "../../../paraglide/messages.js";
import { describeError, type ErrorDescription, isProblem } from "../../problems";

/** What the dialog imports from: a link Jofi fetches, or the pasted text of the posting. */
export type ImportSource = "url" | "text";

/** What the user gave, shown in the dialog as typed. Posting text and links are untrusted data, never markup. */
export interface ImportDraft {
  source: ImportSource;
  url: string;
  text: string;
}

export const EMPTY_DRAFT: ImportDraft = { source: "url", url: "", text: "" };

/** How long the client waits between two looks at a running import. */
export const POLL_INTERVAL_MS = 1500;

const APPLICATION_PROBLEMS = "urn:jofi:problem:applications:";

/** A refused request: which field of the dialog it concerns, and the backend's code. */
export interface ImportViolation {
  source: ImportSource;
  /** `INVALID_URL`, `NOT_ALLOWED`, `UNREACHABLE`, `TIMEOUT`, `TOO_LARGE`, `NOT_HTML`, `LOGIN_REQUIRED`, `NO_TEXT`, ... */
  problem: string;
}

/** Why starting an import failed, so the dialog can show it where it belongs. */
export type ImportStartError =
  | { kind: "violation"; violation: ImportViolation }
  | { kind: "ai-not-configured" }
  | { kind: "in-progress" }
  | { kind: "busy" }
  | { kind: "other"; failure: ErrorDescription };

/** The backend names the link field `originalUrl` (it is the source's URL) and the text field `description`. */
const FIELD_SOURCES: Readonly<Record<string, ImportSource>> = { originalUrl: "url", description: "text" };

/** Sorts a failed start call (400 violation, 409 problems, anything else) into the dialog's cases. */
export function describeStartError(error: unknown): ImportStartError {
  if (isProblem(error, `${APPLICATION_PROBLEMS}ai-not-configured`)) return { kind: "ai-not-configured" };
  if (isProblem(error, `${APPLICATION_PROBLEMS}import-in-progress`)) return { kind: "in-progress" };
  // Before anything generic: a 429 here is the cap on concurrent imports, not the login throttle (`describeError`).
  if (isProblem(error, `${APPLICATION_PROBLEMS}import-busy`)) return { kind: "busy" };
  const violation = violationOf(error);
  return violation ? { kind: "violation", violation } : { kind: "other", failure: describeError(error) };
}

function violationOf(error: unknown): ImportViolation | undefined {
  if (!(error instanceof ApiProblemError) || error.status !== 400) return undefined;
  const violations: unknown = (error.problem as { violations?: unknown }).violations;
  if (!Array.isArray(violations)) return undefined;
  for (const entry of violations as { field?: unknown; problem?: unknown }[]) {
    const source = typeof entry.field === "string" ? FIELD_SOURCES[entry.field] : undefined;
    if (source && typeof entry.problem === "string") return { source, problem: entry.problem };
  }
  return undefined;
}

const URL_MESSAGES: Readonly<Record<string, () => string>> = {
  REQUIRED: m.import_url_required,
  INVALID_URL: m.import_url_invalid,
  NOT_ALLOWED: m.import_url_not_allowed,
  UNREACHABLE: m.import_url_unreachable,
  TIMEOUT: m.import_url_timeout,
  TOO_LARGE: m.import_url_too_large,
  NOT_HTML: m.import_url_not_html,
  REFUSED: m.import_url_refused,
  LOGIN_REQUIRED: m.import_url_login_required,
  NO_TEXT: m.import_url_no_text,
};

const TEXT_MESSAGES: Readonly<Record<string, () => string>> = {
  REQUIRED: m.import_text_required,
  TOO_LONG: m.import_text_too_long,
  INVALID_CHARACTER: m.import_text_invalid_character,
};

/** The message of a violation in the user's language; an unknown code gets a generic one. */
export function violationMessage({ source, problem }: ImportViolation): string {
  const messages = source === "url" ? URL_MESSAGES : TEXT_MESSAGES;
  return (Object.hasOwn(messages, problem) ? messages[problem] : undefined)?.() ?? m.import_violation_other();
}

/** A failed start (or retry) call as one message in the user's language. */
export function startErrorMessage(error: ImportStartError): string {
  switch (error.kind) {
    case "violation":
      return violationMessage(error.violation);
    case "ai-not-configured":
      return m.import_error_ai_not_configured();
    case "in-progress":
      return m.import_error_in_progress();
    case "busy":
      return m.import_error_busy();
    case "other":
      return error.failure.message;
  }
}

/**
 * A link that failed for a reason pasting the text gets around: the dialog offers "Paste the text instead" next to
 * the message. A malformed link or an empty field is the user's to fix.
 */
export function offersPasting(violation: ImportViolation): boolean {
  return (
    violation.source === "url" && violation.problem !== "REQUIRED" && violation.problem !== "INVALID_URL"
  );
}

type Failure = NonNullable<PostingImportResponse["failure"]>;

const FAILURE_MESSAGES: Readonly<Record<Failure, () => string>> = {
  [PostingImportResponseFailure.AI_NOT_CONFIGURED]: m.import_failure_ai_not_configured,
  [PostingImportResponseFailure.AI_AUTHENTICATION_FAILED]: m.import_failure_ai_authentication_failed,
  [PostingImportResponseFailure.AI_UNAVAILABLE]: m.import_failure_ai_unavailable,
  [PostingImportResponseFailure.AI_REJECTED]: m.import_failure_ai_rejected,
  [PostingImportResponseFailure.UNREADABLE_ANSWER]: m.import_failure_unreadable_answer,
  [PostingImportResponseFailure.NOT_A_POSTING]: m.import_failure_not_a_posting,
  [PostingImportResponseFailure.NOT_QUEUED]: m.import_failure_not_queued,
  [PostingImportResponseFailure.NOT_COMPLETED]: m.import_failure_not_completed,
};

/** Why a finished import failed, in the user's language. */
export function failureMessage(failure: PostingImportResponse["failure"]): string {
  return failure ? FAILURE_MESSAGES[failure]() : m.import_failure_not_completed();
}

const RETRYABLE: readonly Failure[] = [
  PostingImportResponseFailure.AI_UNAVAILABLE,
  PostingImportResponseFailure.UNREADABLE_ANSWER,
  PostingImportResponseFailure.NOT_QUEUED,
  PostingImportResponseFailure.NOT_COMPLETED,
];

/** Failures a second try can fix; the others need the user to change the AI setup or the text first. */
export function isRetryable(failure: PostingImportResponse["failure"]): boolean {
  return failure != null && RETRYABLE.includes(failure);
}

const FIXED_IN_AI_SETTINGS: readonly Failure[] = [
  PostingImportResponseFailure.AI_NOT_CONFIGURED,
  PostingImportResponseFailure.AI_AUTHENTICATION_FAILED,
  PostingImportResponseFailure.AI_REJECTED,
];

/** Failures that are fixed in the AI settings. */
export function needsAiSetup(failure: PostingImportResponse["failure"]): boolean {
  return failure != null && FIXED_IN_AI_SETTINGS.includes(failure);
}

/**
 * What the start call answered. A new import is always `PENDING`; one that is already done the moment it is started
 * means the link was imported before (`200`), and nothing new is created.
 */
export function wasAlreadyImported(started: PostingImportResponse): boolean {
  return started.status === "SUCCEEDED";
}

/** What another app shared: each part optional (manifest `share_target.params`). */
export interface SharedContent {
  title?: string;
  text?: string;
  url?: string;
}

const HTTP_URL = /https?:\/\/[^\s<>"']+/i;
const TRAILING_PUNCTUATION = /[.,;:!?]+$/;
const CLOSING_TO_OPENING: Readonly<Record<string, string>> = { ")": "(", "]": "[", "}": "{" };
// Control characters, and the invisible ones that reorder or hide text (bidi marks and overrides, zero-width space,
// word joiner, byte order mark). Zero-width joiners stay: emoji need them.
const INVISIBLE = /[\p{Cc}\u061C\u200B\u200E\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]/gu;

/** What is shown and sent of a shared part: no control or direction-changing characters (line breaks if wanted). */
export function cleanShared(value: string, multiline: boolean): string {
  const lines = multiline ? value.replace(/\r\n?/g, "\n").replace(/\t/g, " ").split("\n") : [value];
  return lines.map((line) => line.replace(INVISIBLE, "")).join("\n");
}

/** How much of a shared text the share page prints; the dialog's field holds all of it. */
export const PREVIEW_LIMIT = 2000;

/** The start of [text] for the page's preview, cut at [PREVIEW_LIMIT] characters (never inside a surrogate pair). */
export function previewOf(text: string): { shown: string; truncated: boolean } {
  if (text.length <= PREVIEW_LIMIT) return { shown: text, truncated: false };
  const cut = text.slice(0, PREVIEW_LIMIT);
  const last = cut.charCodeAt(cut.length - 1);
  return { shown: last >= 0xd800 && last <= 0xdbff ? cut.slice(0, -1) : cut, truncated: true };
}

/** Removes closing brackets that have no opening partner in the link (a sentence's `)`); balanced ones stay. */
function withoutUnbalancedClosers(link: string): string {
  let end = link.length;
  while (end > 0) {
    const closer = link.charAt(end - 1);
    const opening = CLOSING_TO_OPENING[closer];
    if (opening === undefined) break;
    const body = link.slice(0, end - 1);
    if (body.split(opening).length > body.split(closer).length) break;
    end -= 1;
  }
  return link.slice(0, end);
}

function linkIn(text: string | undefined): string | undefined {
  const found = text?.match(HTTP_URL)?.[0];
  if (found === undefined) return undefined;
  let link = found;
  for (let previous = ""; previous !== link; ) {
    previous = link;
    link = withoutUnbalancedClosers(link.replace(TRAILING_PUNCTUATION, ""));
  }
  return link;
}

function httpUrl(candidate: string | undefined): string | undefined {
  if (!candidate) return undefined;
  try {
    const trimmed = candidate.trim();
    const { protocol } = new URL(trimmed);
    return protocol === "http:" || protocol === "https:" ? trimmed : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Turns what a share sheet sent into the dialog's starting values. Apps differ: some put the link in `url`, many
 * (Android) put it into `text`. A link in `url` or `text` makes a link import; otherwise title and text become the text
 * to import. Nothing is fetched or sent here: the user sees the dialog and confirms first.
 */
export function draftFromShare(shared: SharedContent): ImportDraft {
  const link = httpUrl(shared.url) ?? httpUrl(linkIn(shared.text));
  // The text stays in the draft even when a link is taken: "Paste the text instead" (a refused LinkedIn link) needs it.
  const text = [shared.title, shared.text].filter(Boolean).join("\n\n");
  return link ? { ...EMPTY_DRAFT, source: "url", url: link, text } : { ...EMPTY_DRAFT, source: "text", text };
}
