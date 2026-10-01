// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  getGetPipelineOverviewQueryKey,
  getGetPostingImportQueryKey,
  getListRecentActivityQueryKey,
  getPostingImport,
  getSearchApplicationsQueryKey,
  getSearchCompaniesQueryKey,
} from "../../../api/generated/jofi";
import { POLL_INTERVAL_MS } from "./importModel";

/** A new application (and maybe a new company) exists once an import succeeded: the lists ask again. */
export function refreshAfterImport(queryClient: QueryClient) {
  for (const queryKey of [
    getSearchApplicationsQueryKey(),
    getSearchCompaniesQueryKey(),
    getGetPipelineOverviewQueryKey(),
    getListRecentActivityQueryKey(),
  ])
    void queryClient.invalidateQueries({ queryKey });
}

/** How long a closed dialog's import is followed: longer than a run takes, shorter than `PostingImport.STALLED_AFTER`. */
export const WATCH_LIMIT_MS = 5 * 60_000;

const watched = new Set<string>();

/**
 * Follows an import whose dialog was closed while it ran, so the lists still pick up its application ("You can close
 * this window; the import carries on"). It looks every [POLL_INTERVAL_MS], like the dialog, and ends at the first
 * answer that is not pending, at the first failed read (a 401 ends the session elsewhere), or after
 * [WATCH_LIMIT_MS]: no timer outlives that. One watch per import.
 */
export function watchImport(queryClient: QueryClient, importId: string) {
  if (watched.has(importId)) return;
  watched.add(importId);
  const deadline = Date.now() + WATCH_LIMIT_MS;
  const look = async () => {
    try {
      const current = await queryClient.fetchQuery({
        queryKey: getGetPostingImportQueryKey(importId),
        queryFn: () => getPostingImport(importId),
        staleTime: 0,
      });
      if (current.status === "SUCCEEDED") refreshAfterImport(queryClient);
      if (current.status === "PENDING" && Date.now() < deadline) {
        setTimeout(() => void look(), POLL_INTERVAL_MS);
        return;
      }
    } catch {
      // A failed read ends the watch; nothing is shown for it, the list simply is not refreshed.
    }
    watched.delete(importId);
  };
  setTimeout(() => void look(), POLL_INTERVAL_MS);
}
