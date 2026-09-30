// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Alert } from "../../ui";
import type { ErrorDescription } from "../problems";
import type { Throttle } from "./useThrottle";

export interface FormFeedbackProps {
  throttle: Throttle;
  /** A failure that belongs to no single field. */
  failure: ErrorDescription | null;
}

/** The form-level messages of the password forms: backoff wait, "try again now", other errors. */
export function FormFeedback({ throttle, failure }: FormFeedbackProps) {
  if (throttle.waitSeconds !== undefined) {
    return <Alert tone="error">{m.throttled({ seconds: throttle.waitSeconds })}</Alert>;
  }
  if (throttle.waitEnded) {
    return <Alert tone="info">{m.throttled_ready()}</Alert>;
  }
  if (failure) {
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
  return null;
}
