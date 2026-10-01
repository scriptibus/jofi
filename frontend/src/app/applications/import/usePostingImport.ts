// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  getGetPipelineOverviewQueryKey,
  getGetPostingImportQueryKey,
  getListRecentActivityQueryKey,
  getSearchApplicationsQueryKey,
  getSearchCompaniesQueryKey,
  type PostingImportResponse,
  useGetPostingImport,
  useRetryPostingImport,
  useStartPostingImport,
  useStartUrlImport,
} from "../../../api/generated/jofi";
import {
  describeStartError,
  type ImportDraft,
  type ImportStartError,
  POLL_INTERVAL_MS,
  wasAlreadyImported,
} from "./importModel";

const LOCAL = { mutation: { meta: { errorHandledLocally: true } } };

export interface PostingImportFlow {
  /** Starts the import of what the user gave. A second call while one is on its way does nothing. */
  start: (draft: ImportDraft) => void;
  /** Queues a failed import again (the user pressed "Try again"). */
  retry: () => void;
  /** Forgets the import, back to the form. */
  reset: () => void;
  /** Drops the start error (the user is editing the field it was about). */
  dismissError: () => void;
  /** The start call is on its way: the submit control stays disabled. */
  isStarting: boolean;
  /** The start call failed, with the reason. */
  startError: ImportStartError | null;
  /** The import once it was accepted, kept up to date by polling while it is pending. */
  current: PostingImportResponse | undefined;
  /** The link was imported before: nothing new was created. */
  alreadyImported: boolean;
  /** The import's status could not be read (not the import itself failing). */
  statusFailed: boolean;
  /** Reads the status again after `statusFailed`. */
  refreshStatus: () => void;
}

/**
 * The import dialog's state: start a link or text import, poll the import until it is done, retry a failed one.
 * The start is guarded twice against a double submit: the control is disabled while the call is on its way, and a
 * second `start` in the same tick (Enter twice before the next render) is ignored here; the server also answers
 * a repeated request with the first import (#187 finding F6, #208).
 */
export function usePostingImport(): PostingImportFlow {
  const queryClient = useQueryClient();
  const [importId, setImportId] = useState<string | null>(null);
  const [alreadyImported, setAlreadyImported] = useState(false);
  const [startError, setStartError] = useState<ImportStartError | null>(null);
  const inFlight = useRef(false);
  const startText = useStartPostingImport(LOCAL);
  const startUrl = useStartUrlImport(LOCAL);
  const retryImport = useRetryPostingImport(LOCAL);

  const accepted = (response: PostingImportResponse) => {
    queryClient.setQueryData(getGetPostingImportQueryKey(response.id), response);
    setAlreadyImported(wasAlreadyImported(response));
    setImportId(response.id);
  };
  const callbacks = {
    onSuccess: accepted,
    onError: (error: unknown) => setStartError(describeStartError(error)),
    onSettled: () => {
      inFlight.current = false;
    },
  };

  const start = (draft: ImportDraft) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setStartError(null);
    if (draft.source === "url") startUrl.mutate({ data: { url: draft.url.trim() } }, callbacks);
    else startText.mutate({ data: { description: draft.text } }, callbacks);
  };

  const retry = () => {
    if (inFlight.current || importId === null) return;
    inFlight.current = true;
    setStartError(null);
    retryImport.mutate({ importId }, callbacks);
  };

  const status = useGetPostingImport(importId ?? "", {
    query: {
      enabled: importId !== null,
      // Stops at the first failed read (after the client's own retries); "Check again" asks once more.
      refetchInterval: (query) =>
        query.state.status !== "error" && query.state.data?.status === "PENDING" ? POLL_INTERVAL_MS : false,
      meta: { errorHandledLocally: true },
    },
  });
  useRefreshListsWhenDone(status.data);

  return {
    start,
    retry,
    dismissError: () => setStartError(null),
    reset: () => {
      setImportId(null);
      setStartError(null);
      setAlreadyImported(false);
    },
    isStarting: startText.isPending || startUrl.isPending || retryImport.isPending,
    startError,
    current: importId === null ? undefined : status.data,
    alreadyImported,
    statusFailed: importId !== null && status.isError && (status.data?.status ?? "PENDING") === "PENDING",
    refreshStatus: () => void status.refetch(),
  };
}

/** A new application (and maybe a new company) exists once an import succeeded: the lists ask again, once. */
function useRefreshListsWhenDone(current: PostingImportResponse | undefined) {
  const queryClient = useQueryClient();
  const refreshed = useRef<string | null>(null);
  useEffect(() => {
    if (current?.status !== "SUCCEEDED" || refreshed.current === current.id) return;
    refreshed.current = current.id;
    for (const queryKey of [
      getSearchApplicationsQueryKey(),
      getSearchCompaniesQueryKey(),
      getGetPipelineOverviewQueryKey(),
      getListRecentActivityQueryKey(),
    ])
      void queryClient.invalidateQueries({ queryKey });
  }, [current, queryClient]);
}
