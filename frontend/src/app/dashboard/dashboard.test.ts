// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
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

  it("names the kind of record and what happened to it", () => {
    expect(said("application", "Created application")).toBe("Application created");
    expect(said("application", "Edited application; also changed: portal notes, pay band")).toBe(
      "Application changed",
    );
    expect(said("application", "Changed application status")).toBe("Status changed");
    expect(said("application_source", "Added source")).toBe("Source added");
    expect(said("description_snapshot", "Froze job description")).toBe("Job description frozen");
    expect(said("interview", "Deleted interview")).toBe("Interview deleted");
    expect(said("company", "Changed company preference; reason changed")).toBe("Company changed");
    expect(said("contact", "Deleted with its company")).toBe("Contact deleted");
    expect(said("task", "Completed task")).toBe("Task done");
    expect(said("task", "Reopened task")).toBe("Task reopened");
    expect(said("task", "Dismissed obsolete suggestion")).toBe("Suggestion dismissed");
    expect(said("task", "Accepted suggestion")).toBe("Suggestion accepted");
    expect(said("countdown", "Edited countdown; also changed: title")).toBe("Countdown changed");
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
