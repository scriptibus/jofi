// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { aSavedView } from "../../test/fakeSavedViewBackend";
import { daysAgo } from "./applicationsSearch";
import { filterForRename, openView, toViewFilter, updatedWithin } from "./savedViews";

const company = "0b6f2c1e-6a51-4c1c-9f4e-2d7b8a3c9e10";
const savedAt = new Date(2026, 8, 30, 15, 30);

describe("toViewFilter", () => {
  it("stores every filter and the order under the list's query parameter names", () => {
    expect(
      toViewFilter(
        {
          q: "engineer",
          status: ["APPLIED", "OFFER"],
          company,
          source: ["SCANNER"],
          language: "de",
          unread: true,
          updated: 30,
          wantMin: 3.5,
          fitMin: 2,
          sort: "DEADLINE",
          page: 3,
          view: "board",
        },
        savedAt,
      ),
    ).toEqual({
      search: "engineer",
      status: ["APPLIED", "OFFER"],
      companyId: company,
      sourceKind: ["SCANNER"],
      language: ["de"],
      unread: true,
      updatedFrom: daysAgo(30, savedAt),
      wantMin: 3.5,
      fitMin: 2,
      sort: "DEADLINE",
      direction: "ASCENDING",
    });
  });

  it("stores nothing for an unfiltered list in the default order", () => {
    expect(toViewFilter({})).toEqual({});
  });
});

describe("openView", () => {
  it("restores what toViewFilter stored, counting 'updated within' from the moment it was stored", () => {
    const search = {
      q: "engineer",
      status: ["APPLIED" as const],
      company,
      source: ["URL" as const],
      language: "en",
      unread: true as const,
      updated: 7 as const,
      wantMin: 4,
      fitMin: 1.5,
      sort: "TITLE" as const,
      dir: "DESCENDING" as const,
    };
    const view = aSavedView({ filter: toViewFilter(search, savedAt), updatedAt: savedAt.toISOString() });
    expect(openView(view)).toEqual({ search, leftOut: false });
  });

  it("keeps a default direction out of the URL", () => {
    const view = aSavedView({ filter: { sort: "UPDATED", direction: "DESCENDING" } });
    expect(openView(view).search).toEqual({ sort: "UPDATED" });
  });

  it("reports filters this page has no control for and leaves them out", () => {
    for (const filter of [
      { contactId: company },
      { wantMax: 4 },
      { createdFrom: "2026-01-01T00:00:00Z" },
      { language: ["de", "en"] },
      { unread: false },
      { updatedFrom: "2026-09-27T00:00:00Z" },
    ]) {
      const opened = openView(aSavedView({ filter: { ...filter, search: "kept" } }));
      expect(opened).toEqual({ search: { q: "kept" }, leftOut: true });
    }
  });
});

describe("updatedWithin", () => {
  it("counts calendar days between the start day and the moment the view was stored", () => {
    expect(updatedWithin(daysAgo(90, savedAt), savedAt.toISOString())).toBe(90);
    expect(updatedWithin(daysAgo(5, savedAt), savedAt.toISOString())).toBeUndefined();
  });
});

describe("filterForRename", () => {
  it("counts 'updated within' again from now and keeps every other filter as stored", () => {
    const view = aSavedView({
      filter: { updatedFrom: daysAgo(7, savedAt), contactId: company, wantMax: 4 },
      updatedAt: savedAt.toISOString(),
    });
    const now = new Date(2026, 9, 20, 9, 0);
    expect(filterForRename(view, now)).toEqual({
      updatedFrom: daysAgo(7, now),
      contactId: company,
      wantMax: 4,
    });
  });

  it("sends a filter without 'updated within' unchanged", () => {
    const view = aSavedView({ filter: { updatedFrom: "2026-01-01T00:00:00Z", search: "x" } });
    expect(filterForRename(view)).toBe(view.filter);
  });
});
