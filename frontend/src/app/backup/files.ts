// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { getLocale } from "../../paraglide/runtime.js";

/** Used when the server named no file (it always does; ADR-0042 `jofi-backup-<yyyyMMdd-HHmmss>.zip`). */
export const FALLBACK_BACKUP_NAME = "jofi-backup.zip";

/** How long the download link stays valid: the browser must have started reading the blob by then. */
const REVOKE_AFTER_MS = 10_000;

/** The file name the server gave the download, if it is a zip; otherwise the fallback. */
export function backupFileName(blob: Blob): string {
  return blob instanceof File && blob.name.toLowerCase().endsWith(".zip") ? blob.name : FALLBACK_BACKUP_NAME;
}

/**
 * Hands a downloaded blob to the browser as a file download. The export is a POST with the password in
 * its body, so a plain link cannot start it; the zip arrives through fetch and is saved from memory.
 */
export function saveFile(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.rel = "noopener";
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  // Revoking at once can cancel the download in some browsers; the blob is freed shortly after.
  window.setTimeout(() => URL.revokeObjectURL(url), REVOKE_AFTER_MS);
}

/** An instant (ISO 8601) as date and time in the user's language; the input itself if it is no date. */
export function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(getLocale(), { dateStyle: "medium", timeStyle: "short" }).format(date);
}

/** A count with the user's digit grouping (1,234 / 1.234). */
export function formatCount(value: number): string {
  return new Intl.NumberFormat(getLocale()).format(value);
}
