// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { m } from "../../paraglide/messages.js";
import { getLocale, overwriteGetLocale } from "../../paraglide/runtime.js";
import { anActivityEntry, aPipeline } from "../../test/fakeDashboardBackend";
import { describeActivity, formatCount, formatRate, occupiedStatuses } from "./dashboard";

describe("formatCount and formatRate", () => {
  it("format numbers in the user's locale", () => {
    expect(formatCount(1234, "en")).toBe("1,234");
    expect(formatCount(1234, "de")).toBe("1.234");
    expect(formatRate(0.4, "en")).toBe("40%");
    // German puts a no-break space before the sign.
    expect(formatRate(0.4, "de")).toBe("40 %");
    expect(formatRate(2 / 3, "en")).toBe("67%");
  });

  it("has no rate while its base is zero", () => {
    expect(formatRate(null, "en")).toBeNull();
    expect(formatRate(undefined, "en")).toBeNull();
    expect(formatRate(0, "en")).toBe("0%");
  });
});

describe("the response rate's sentence", () => {
  const sentence = (responded: number, applied: number, locale: "en" | "de") =>
    m.dashboard_funnel_response(
      { responded: String(responded), applied: String(applied), count: applied },
      { locale },
    );

  it("has a singular and a plural form in both languages", () => {
    expect(sentence(1, 1, "en")).toBe("Response rate: 1 of 1 application got an answer.");
    expect(sentence(0, 1, "en")).toBe("Response rate: 0 of 1 application got an answer.");
    expect(sentence(2, 5, "en")).toBe("Response rate: 2 of 5 applications got an answer.");
    expect(sentence(1, 1, "de")).toBe("Antwortquote: 1 von 1 Bewerbung bekam eine Antwort.");
    expect(sentence(2, 5, "de")).toBe("Antwortquote: 2 von 5 Bewerbungen bekamen eine Antwort.");
  });
});

describe("occupiedStatuses", () => {
  it("keeps the statuses that hold applications, in pipeline order", () => {
    const { byStatus } = aPipeline({ OFFER: 1, DISCOVERED: 4, GHOSTED: 2 });
    expect(occupiedStatuses(byStatus).map(({ status }) => status)).toEqual([
      "DISCOVERED",
      "OFFER",
      "GHOSTED",
    ]);
    expect(occupiedStatuses(aPipeline().byStatus)).toEqual([]);
  });
});

describe("describeActivity", () => {
  const said = (entityType: string, description: string) =>
    describeActivity(anActivityEntry({ entityType, description }));

  // Every description the backend writes for the eight activity entity types (backend `ActivityQuery.ENTITY_TYPES`;
  // found by reading each `changelog.record` / `ChangeSummary` of applications, companies and tasks). A new or
  // reworded description belongs here, or it reads as "… changed".
  it.each([
    ["application", "Created application", "Application created"],
    [
      "application",
      "Created application; also changed: portal notes, pay band, offer",
      "Application created",
    ],
    ["application", "Edited application", "Application changed"],
    ["application", "Edited application; also changed: portal notes, pay band, offer", "Application changed"],
    ["application", "Deleted application", "Application deleted"],
    ["application", "Changed application status", "Status changed"],
    ["application", "Corrected decline reason", "Application changed"],
    ["application", "Marked application read", "Application marked as read"],
    ["application", "Marked application unread", "Application marked as unread"],
    ["application", "Changed linked contacts", "Application changed"],
    ["application", "Unlinked a deleted contact", "Application changed"],
    ["application_source", "Added source", "Source added"],
    ["description_snapshot", "Recorded job description", "Job description added"],
    ["description_snapshot", "Froze job description", "Job description frozen"],
    ["interview", "Logged interview", "Interview created"],
    ["interview", "Logged interview; also changed: participants, notes", "Interview created"],
    ["interview", "Edited interview", "Interview changed"],
    ["interview", "Deleted interview", "Interview deleted"],
    ["interview", "Removed a deleted contact from the participants", "Interview changed"],
    ["company", "Created company", "Company created"],
    ["company", "Created company; research notes changed", "Company created"],
    ["company", "Edited company", "Company changed"],
    ["company", "Changed company preference", "Company changed"],
    ["company", "Changed company preference; reason changed", "Company changed"],
    ["company", "Deleted company", "Company deleted"],
    ["contact", "Created contact", "Contact created"],
    ["contact", "Edited contact; fields: role, channels", "Contact changed"],
    ["contact", "Deleted contact", "Contact deleted"],
    ["contact", "Deleted with its company", "Contact deleted"],
    ["task", "Created task", "Task created"],
    ["task", "Suggested task", "Task suggested"],
    ["task", "Edited task; also changed: title, notes", "Task changed"],
    ["task", "Completed task", "Task done"],
    ["task", "Reopened task", "Task reopened"],
    ["task", "Accepted suggestion", "Suggestion accepted"],
    ["task", "Dismissed suggestion", "Suggestion dismissed"],
    ["task", "Dismissed obsolete suggestion", "Suggestion dismissed"],
    ["task", "Deleted task", "Task deleted"],
    ["task", "Cleared the link to a deleted application", "Task changed"],
    ["task", "Cleared the link to a deleted company", "Task changed"],
    ["task", "Cleared the link to a deleted contact", "Task changed"],
    ["countdown", "Created countdown", "Countdown created"],
    ["countdown", "Edited countdown; also changed: title", "Countdown changed"],
    ["countdown", "Deleted countdown", "Countdown deleted"],
  ])("%s: “%s” reads as “%s”", (entityType, description, sentence) => {
    expect(said(entityType, description)).toBe(sentence);
  });

  it("falls back to a change of an entry for what this version does not know", () => {
    expect(said("application", "Merged duplicates")).toBe("Application changed");
    expect(said("scanner_finding", "Created finding")).toBe("Entry created");
  });

  it("speaks the user's language", () => {
    const original = getLocale;
    overwriteGetLocale(() => "de");
    try {
      expect(said("application", "Created application")).toBe("Bewerbung angelegt");
      expect(said("task", "Completed task")).toBe("Aufgabe erledigt");
      expect(said("application", "Changed application status")).toBe("Status geändert");
    } finally {
      overwriteGetLocale(original);
    }
  });
});
