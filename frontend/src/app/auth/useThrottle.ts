// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useCallback, useEffect, useState } from "react";

export interface Throttle {
  /** Seconds the server asked to wait, while the wait lasts; undefined otherwise. */
  waitSeconds: number | undefined;
  /** True once a wait has ended, until the next attempt (to say "try again now"). */
  waitEnded: boolean;
  /** Starts a wait of `seconds` (from `Retry-After`). */
  start: (seconds: number) => void;
  /** Forget the ended wait (on the next attempt). */
  reset: () => void;
}

/**
 * The login backoff as UI state: after a 429 the form keeps its submit button disabled for the
 * `Retry-After` seconds, then says the user may try again. The message states the wait once
 * instead of ticking, so screen readers are not flooded.
 */
export function useThrottle(): Throttle {
  const [waitSeconds, setWaitSeconds] = useState<number | undefined>(undefined);
  const [waitEnded, setWaitEnded] = useState(false);

  useEffect(() => {
    if (waitSeconds === undefined) return;
    const timer = window.setTimeout(() => {
      setWaitSeconds(undefined);
      setWaitEnded(true);
    }, waitSeconds * 1000);
    return () => window.clearTimeout(timer);
  }, [waitSeconds]);

  const start = useCallback((seconds: number) => {
    setWaitEnded(false);
    setWaitSeconds(Math.max(1, seconds));
  }, []);
  const reset = useCallback(() => setWaitEnded(false), []);

  return { waitSeconds, waitEnded, start, reset };
}
