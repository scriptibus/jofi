// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { type Status, statusLabels } from "./labels";

/**
 * The status as a word in a frame. Standing alone it says "Status: …" to screen readers; inside a sentence
 * that already says what it is (the status history), `bare` leaves that out.
 */
export function StatusBadge({ status, bare = false }: { status: Status; bare?: boolean }) {
  return (
    <span className="inline-flex w-fit items-center gap-1.5 rounded border border-line bg-sunken px-2 py-0.5 font-semibold text-body">
      {bare ? null : <span className="sr-only">{m.application_status_label()}: </span>}
      {statusLabels[status]()}
    </span>
  );
}
