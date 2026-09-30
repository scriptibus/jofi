// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  type CompanyResponse,
  getGetCompanyQueryKey,
  getSearchCompaniesQueryKey,
} from "../../api/generated/jofi";

/** After a save: the cache holds the saved company, and every list asks the server again. */
export function storeSaved(queryClient: QueryClient, company: CompanyResponse) {
  queryClient.setQueryData(getGetCompanyQueryKey(company.id), company);
  void queryClient.invalidateQueries({ queryKey: getSearchCompaniesQueryKey() });
}

/** After a delete: the company is gone from the cache, and every list asks the server again. */
export function forgetDeleted(queryClient: QueryClient, id: string) {
  queryClient.removeQueries({ queryKey: getGetCompanyQueryKey(id), exact: true });
  void queryClient.invalidateQueries({ queryKey: getSearchCompaniesQueryKey() });
}
