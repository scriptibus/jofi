// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";

/**
 * The detail page's tabs (spec §6.3); Documents follows in M3. Interviews and calls (§6.1) get a tab of their
 * own to log, edit and delete them; the Timeline shows them among everything else.
 */
export type ApplicationTab = "overview" | "description" | "contacts" | "interviews" | "timeline";

/**
 * In order. A tab that is not `ready` shows in the bar but cannot be selected, so the page's structure is
 * visible from the start; all of them are ready now, Documents (M3) will start as not ready.
 */
export const TABS: readonly { id: ApplicationTab; ready: boolean }[] = [
  { id: "overview", ready: true },
  { id: "description", ready: true },
  { id: "contacts", ready: true },
  { id: "interviews", ready: true },
  { id: "timeline", ready: true },
];

export const tabLabels: Record<ApplicationTab, () => string> = {
  overview: m.application_tab_overview,
  description: m.application_tab_description,
  contacts: m.application_tab_contacts,
  interviews: m.application_tab_interviews,
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
