// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  type ContactResponse,
  getGetContactQueryKey,
  getSearchContactsQueryKey,
} from "../../api/generated/jofi";

/** After a save: the cache holds the saved contact, and every list (a company's too) asks again. */
export function storeSavedContact(queryClient: QueryClient, contact: ContactResponse) {
  queryClient.setQueryData(getGetContactQueryKey(contact.id), contact);
  void queryClient.invalidateQueries({ queryKey: getSearchContactsQueryKey() });
}

/** After a delete: the contact is gone from the cache, and every list (a company's too) asks again. */
export function forgetDeletedContact(queryClient: QueryClient, id: string) {
  queryClient.removeQueries({ queryKey: getGetContactQueryKey(id), exact: true });
  void queryClient.invalidateQueries({ queryKey: getSearchContactsQueryKey() });
}
