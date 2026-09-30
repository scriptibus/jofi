// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Alert } from "../../ui";
import { type NoticeStore, useNotices } from "../notices";

/** The global problem-details display: failed requests no form handled, newest last. */
export function ProblemNotices({ store }: { store: NoticeStore }) {
  const notices = useNotices(store);
  if (notices.length === 0) return null;
  return (
    <div className="flex flex-col gap-3">
      {notices.map((notice) => (
        <Alert
          key={notice.id}
          tone="error"
          title={m.error_heading()}
          dismissLabel={m.error_dismiss()}
          onDismiss={() => store.dismiss(notice.id)}
        >
          <p>{notice.message}</p>
          {notice.detail ? (
            <p className="text-muted">
              {m.error_details()}: <span lang="en">{notice.detail}</span>
            </p>
          ) : null}
        </Alert>
      ))}
    </div>
  );
}
