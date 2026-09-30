// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { afterEach, describe, expect, it } from "vitest";
import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { overwriteGetLocale } from "../../paraglide/runtime.js";
import { describeBackupError } from "./backupProblems";
import { backupFileName, FALLBACK_BACKUP_NAME, formatCount, formatDateTime } from "./files";
import { describeRestore } from "./RestoreBackup";

const refused = (reason: unknown, status = 422) =>
  new ApiProblemError(status, { type: "urn:jofi:problem:system:backup-refused", status, reason });

afterEach(() => overwriteGetLocale(() => "en"));

describe("describeBackupError", () => {
  it("names every refusal reason, and an unknown one by its code", () => {
    expect(describeBackupError(refused("schema-older")).message).toMatch(/Restore it with the Jofi version/);
    expect(describeBackupError(refused("keyset-missing")).message).toMatch(/not the key that decrypts/);
    expect(describeBackupError(refused("toString")).message).toBe(
      "This backup cannot be restored (reason: toString).",
    );
    expect(describeBackupError(refused(42)).message).toBe(
      "This backup cannot be restored (reason: unknown).",
    );
  });

  it("treats any 413 as too large, also from a proxy in front of Jofi", () => {
    expect(describeBackupError(new ApiProblemError(413, { status: 413 })).message).toMatch(/larger than/);
  });

  it("explains a confirmation for something else, and falls back to the general messages", () => {
    const mismatch = new ConfirmationMismatchError(
      { operation: "system.backup.restore", targets: ["a"] },
      { operation: "system.backup.restore", targets: ["b"] },
    );
    expect(describeBackupError(mismatch).message).toMatch(/something other than this backup/);
    expect(describeBackupError(new TypeError("Failed to fetch")).message).toMatch(/cannot reach the server/);
    expect(describeBackupError(new ApiProblemError(503, { status: 503 })).message).toMatch(/temporarily/);
  });

  it("speaks German", () => {
    overwriteGetLocale(() => "de");
    expect(describeBackupError(refused("schema-newer")).message).toBe(
      "Diese Sicherung stammt von einem neueren Jofi. Aktualisiere zuerst Jofi und spiel sie dann zurück.",
    );
  });
});

describe("backup files and formats", () => {
  it("keeps the server's zip name, else a fallback", () => {
    expect(backupFileName(new File(["x"], "jofi-backup-20260930-120000.zip"))).toBe(
      "jofi-backup-20260930-120000.zip",
    );
    expect(backupFileName(new File(["x"], "page.html"))).toBe(FALLBACK_BACKUP_NAME);
    expect(backupFileName(new Blob(["x"]))).toBe(FALLBACK_BACKUP_NAME);
  });

  it("formats dates and counts in the user's language", () => {
    expect(formatCount(1234567)).toBe("1,234,567");
    expect(formatDateTime("not a date")).toBe("not a date");
    overwriteGetLocale(() => "de");
    expect(formatCount(1234567)).toBe("1.234.567");
    expect(formatDateTime("2026-09-30T12:00:00Z")).toMatch(/30\.09\.2026/);
  });

  it("describes the restore from the server's effect", () => {
    const text = describeRestore({
      kind: "backup",
      name: "2026-09-30T12:00:00Z",
      counts: { rows: 2, files: 0 },
    });
    expect(text).toMatch(/2 database rows and 0 files, without a key for stored API keys/);
  });
});
