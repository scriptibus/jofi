// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { ApiProblemError } from "../api/fetcher";
import type { NoticeStore } from "./notices";
import { describeError, isSessionEnded } from "./problems";

/** Our `meta` on queries and mutations. */
export interface RequestMeta extends Record<string, unknown> {
  /** The component shows this request's errors itself (e.g. a form), so no global notice. */
  errorHandledLocally?: boolean;
}

declare module "@tanstack/react-query" {
  interface Register {
    queryMeta: RequestMeta;
    mutationMeta: RequestMeta;
  }
}

export interface QueryClientHooks {
  /** Global problem-details display. */
  notices: NoticeStore;
  /** A request answered 401 `not-logged-in`: the session expired or ended elsewhere. */
  onSessionEnded: () => void;
}

const MAX_RETRIES = 2;

/** Retries only what may heal by itself: network failures and 5xx, never a 4xx answer. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiProblemError && error.status < 500) return false;
  return failureCount < MAX_RETRIES;
}

/**
 * The app's QueryClient. Every failed query or mutation passes one handler: a 401 ends the session
 * (back to login), anything else becomes a problem notice unless the caller handles it locally.
 */
export function createAppQueryClient({ notices, onSessionEnded }: QueryClientHooks): QueryClient {
  const handle = (error: Error, meta: RequestMeta | undefined) => {
    if (isSessionEnded(error)) {
      onSessionEnded();
      return;
    }
    if (meta?.errorHandledLocally) return;
    notices.publish(describeError(error));
  };

  return new QueryClient({
    queryCache: new QueryCache({ onError: (error, query) => handle(error, query.meta) }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => handle(error, mutation.meta),
    }),
    defaultOptions: {
      queries: { retry: shouldRetry, staleTime: 30_000 },
      mutations: { retry: false },
    },
  });
}
