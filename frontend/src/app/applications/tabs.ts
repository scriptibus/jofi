// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";

/** The detail page's tabs (spec §6.3); Documents follows in M3. */
export type ApplicationTab = "overview" | "description" | "contacts" | "timeline";

/**
 * In order. A tab that is not `ready` shows in the bar but cannot be selected, so the page's structure is
 * visible from the start; Description (#105), Contacts (#104) and Timeline (#106) switch theirs on.
 */
export const TABS: readonly { id: ApplicationTab; ready: boolean }[] = [
  { id: "overview", ready: true },
  { id: "description", ready: false },
  { id: "contacts", ready: false },
  { id: "timeline", ready: false },
];

export const tabLabels: Record<ApplicationTab, () => string> = {
  overview: m.application_tab_overview,
  description: m.application_tab_description,
  contacts: m.application_tab_contacts,
  timeline: m.application_tab_timeline,
};

export interface ApplicationSearch {
  /** Undefined for the default tab, Overview. */
  tab?: ApplicationTab | undefined;
}

/**
 * `?tab=` of the detail page: a ready tab other than Overview, else undefined (an old or unknown link).
 * Always sets `tab`: the router merges this result over the raw search, so leaving it out would keep an
 * unknown value.
 */
export function parseApplicationSearch(search: Record<string, unknown>): ApplicationSearch {
  const tab = TABS.find(({ id, ready }) => ready && id === search.tab && id !== "overview");
  return { tab: tab?.id };
}
