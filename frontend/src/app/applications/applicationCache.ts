// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  type ApplicationResponse,
  getGetApplicationQueryKey,
  getSearchApplicationsQueryKey,
} from "../../api/generated/jofi";

/** After a save or a read/unread change: the cache holds the answer, and every list asks again. */
export function storeSavedApplication(queryClient: QueryClient, application: ApplicationResponse) {
  queryClient.setQueryData(getGetApplicationQueryKey(application.id), application);
  void queryClient.invalidateQueries({ queryKey: getSearchApplicationsQueryKey() });
}

/** After a delete: the application is gone from the cache, and every list (a company's too) asks again. */
export function forgetDeletedApplication(queryClient: QueryClient, id: string) {
  queryClient.removeQueries({ queryKey: getGetApplicationQueryKey(id), exact: true });
  void queryClient.invalidateQueries({ queryKey: getSearchApplicationsQueryKey() });
}
