// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useInfiniteQuery } from "@tanstack/react-query";
import { getListInterviewsQueryKey, listInterviews } from "../../api/generated/jofi";

/** The server's largest page (backend `PageRequest.MAX_SIZE`, ADR-0056). */
export const INTERVIEW_PAGE_SIZE = 50;

/**
 * The application's interviews in start order, a page at a time. The key starts with the generated list key, so
 * `refreshInterviews` (which invalidates that one) loads every page held here again.
 */
export function useInterviewPages(applicationId: string) {
  return useInfiniteQuery({
    queryKey: [...getListInterviewsQueryKey(applicationId), "pages"],
    queryFn: ({ pageParam }) =>
      listInterviews(applicationId, { page: pageParam, size: INTERVIEW_PAGE_SIZE, direction: "ASCENDING" }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page.hasMore ? last.page.page + 1 : undefined),
    meta: { errorHandledLocally: true },
  });
}
