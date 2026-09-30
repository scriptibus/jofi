// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { ExportBackup } from "./ExportBackup";
import { RestoreBackup } from "./RestoreBackup";

/** Settings > Backup (spec §3.1, ADR-0042): download a backup, restore one. */
export function BackupSection() {
  return (
    <section aria-labelledby="backup-heading" className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h2 id="backup-heading" className="text-h2">
          {m.backup_heading()}
        </h2>
        <p className="text-muted">{m.backup_intro()}</p>
      </div>
      <div className="grid gap-8 lg:grid-cols-2">
        <ExportBackup />
        <RestoreBackup />
      </div>
    </section>
  );
}
