// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Alert } from "../../ui";
import type { ErrorDescription } from "../problems";

/** A failed setup call next to what failed, with the server's English detail for unknown problems. */
export function FailureAlert({ failure }: { failure: ErrorDescription | null }) {
  if (failure === null) return null;
  return (
    <Alert tone="error" title={m.error_heading()}>
      <p>{failure.message}</p>
      {failure.detail ? (
        <p className="text-muted">
          {m.error_details()}: <span lang="en">{failure.detail}</span>
        </p>
      ) : null}
    </Alert>
  );
}
