// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type QueryClient, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  type ApplicationResponse,
  type ContactResponse,
  getApplication,
  linkApplicationContacts,
} from "../../api/generated/jofi";
import { storeSavedApplication } from "./applicationCache";
import { isApplicationVersionConflict } from "./applicationProblems";

/** The most contacts one application links (backend `Application.MAX_CONTACTS`). */
export const MAX_CONTACTS = 50;

/** The linked ids with `contactId` added at the end (once). */
export function withContact(contactIds: readonly string[], contactId: string): string[] {
  return contactIds.includes(contactId) ? [...contactIds] : [...contactIds, contactId];
}

export function withoutContact(contactIds: readonly string[], contactId: string): string[] {
  return contactIds.filter((id) => id !== contactId);
}

export interface PickerChoices {
  /** Contacts of the application's company, best match first. */
  atCompany: ContactResponse[];
  /** Every other contact, best match first. */
  others: ContactResponse[];
}

/**
 * What the picker offers: the company's contacts first (the likely ones), then all others, each in the
 * server's order and without the contacts already linked. A contact appears once.
 */
export function pickerChoices(
  companyContacts: readonly ContactResponse[],
  allContacts: readonly ContactResponse[],
  linkedIds: readonly string[],
): PickerChoices {
  const linked = new Set(linkedIds);
  const atCompany = companyContacts.filter((contact) => !linked.has(contact.id));
  const shown = new Set([...linked, ...atCompany.map((contact) => contact.id)]);
  const others = allContacts.filter((contact) => !shown.has(contact.id));
  return { atCompany, others };
}

/**
 * Links a contact that was just created to the application: reads the application's current links and
 * version, then saves them with the new contact. The user did not see a version here, so a save that races
 * with another one is tried once more on the newer version instead of asking.
 */
export async function linkCreatedContact(
  queryClient: QueryClient,
  applicationId: string,
  contactId: string,
): Promise<ApplicationResponse> {
  const attempt = async () => {
    const current = await getApplication(applicationId);
    return linkApplicationContacts(applicationId, {
      contactIds: withContact(current.contactIds, contactId),
      basedOnVersion: current.version,
    });
  };
  const saved = await attempt().catch((error: unknown) => {
    if (isApplicationVersionConflict(error)) return attempt();
    throw error;
  });
  storeSavedApplication(queryClient, saved);
  return saved;
}

/** `linkCreatedContact` as a mutation; a failure becomes the app's global notice (the contact is saved). */
export function useLinkCreatedContact(applicationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (contactId: string) => linkCreatedContact(queryClient, applicationId, contactId),
  });
}
