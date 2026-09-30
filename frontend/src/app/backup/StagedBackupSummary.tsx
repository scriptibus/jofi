// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { StagedBackupResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { formatCount, formatDateTime } from "./files";

/** What an uploaded backup contains, from the server's check of its manifest, before anything is replaced. */
export function StagedBackupSummary({ backup }: { backup: StagedBackupResponse }) {
  const facts: [string, string][] = [
    [m.backup_staged_created(), formatDateTime(backup.createdAt)],
    [m.backup_staged_app_version(), backup.appVersion],
    [m.backup_staged_schema(), backup.schemaVersion],
    [
      m.backup_staged_migration(),
      backup.migratedFrom
        ? m.backup_staged_migrated({ from: backup.migratedFrom })
        : m.backup_staged_not_migrated(),
    ],
    [m.backup_staged_rows(), formatCount(backup.rows)],
    [m.backup_staged_files(), formatCount(backup.files)],
    [
      m.backup_staged_keyset(),
      backup.includesKeyset ? m.backup_staged_keyset_yes() : m.backup_staged_keyset_no(),
    ],
  ];
  return (
    <section
      aria-labelledby="backup-staged-heading"
      className="flex flex-col gap-3 rounded border border-line p-4"
    >
      <h4 id="backup-staged-heading" className="font-semibold text-body">
        {m.backup_staged_heading()}
      </h4>
      <dl className="grid gap-3 sm:grid-cols-2">
        {facts.map(([term, value]) => (
          <div key={term} className="flex min-w-0 flex-col gap-0.5">
            <dt className="font-data text-eyebrow text-muted">{term}</dt>
            <dd className="break-words">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
