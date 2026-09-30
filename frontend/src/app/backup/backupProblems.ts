// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { m } from "../../paraglide/messages.js";
import { describeError, type ErrorDescription } from "../problems";

const BACKUP = "urn:jofi:problem:system:backup-";

/** Problem types of the backup endpoints (backend `BackupProblems`, ADR-0042). */
export const BackupProblemType = {
  refused: `${BACKUP}refused`,
  notFound: `${BACKUP}not-found`,
  unavailable: `${BACKUP}unavailable`,
  busy: `${BACKUP}busy`,
  incomplete: `${BACKUP}restore-incomplete`,
} as const;

/** Why the server refused an uploaded archive: the `reason` member of a `backup-refused` problem. */
const refusals: Record<string, () => string> = {
  "not-a-backup": m.backup_refused_not_a_backup,
  "too-large": m.backup_refused_too_large,
  "too-many-entries": m.backup_refused_too_many_entries,
  "unsafe-path": m.backup_refused_unsafe_path,
  "duplicate-entry": m.backup_refused_duplicate_entry,
  "manifest-invalid": m.backup_refused_manifest_invalid,
  "unsupported-format": m.backup_refused_unsupported_format,
  "schema-older": m.backup_refused_schema_older,
  "schema-newer": m.backup_refused_schema_newer,
  "content-mismatch": m.backup_refused_content_mismatch,
  "tables-mismatch": m.backup_refused_tables_mismatch,
  "keyset-missing": m.backup_refused_keyset_missing,
  "keyset-mismatch": m.backup_refused_keyset_mismatch,
  "data-invalid": m.backup_refused_data_invalid,
  "account-missing": m.backup_refused_account_missing,
};

const knownMessages: Record<string, () => string> = {
  [BackupProblemType.busy]: m.backup_error_busy,
  [BackupProblemType.unavailable]: m.backup_error_unavailable,
  [BackupProblemType.incomplete]: m.backup_error_incomplete,
  [BackupProblemType.notFound]: m.backup_error_not_found,
};

function refusal(error: ApiProblemError): ErrorDescription {
  const reason = typeof error.problem.reason === "string" ? error.problem.reason : "unknown";
  const known = Object.hasOwn(refusals, reason) ? refusals[reason] : undefined;
  return { message: known ? known() : m.backup_refused_other({ reason }) };
}

/**
 * A failed backup call in the user's language: each refusal reason and backup problem has its own text,
 * a 413 from anything in front of Jofi counts as "too large", the rest goes to `describeError`
 * (wrong password and the backoff are handled at the form, before this).
 */
export function describeBackupError(error: unknown): ErrorDescription {
  if (error instanceof ConfirmationMismatchError) return { message: m.backup_error_mismatch() };
  if (!(error instanceof ApiProblemError)) return describeError(error);
  const type = error.problem.type;
  if (type === BackupProblemType.refused) return refusal(error);
  const known = type === undefined ? undefined : knownMessages[type];
  if (known) return { message: known() };
  if (error.status === 413) return { message: m.backup_refused_too_large() };
  return describeError(error);
}
