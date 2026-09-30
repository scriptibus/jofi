// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  currentOrder,
  currentView,
  daysAgo,
  isFiltered,
  parseApplicationsSearch,
  parseNewApplicationSearch,
  sortedBy,
  toBoardSearchParams,
  toSearchParams,
  withFilter,
  withOrder,
  withoutFilters,
  withPage,
  withView,
} from "./applicationsSearch";

const COMPANY = "0b7c8f2e-3a41-4d6e-9f10-2c3d4e5f6a7b";

describe("applications search in the URL", () => {
  it("keeps every valid filter, the order and the page", () => {
    expect(
      parseApplicationsSearch({
        q: "  backend ",
        status: ["OFFER", "APPLIED", "OFFER"],
        company: COMPANY,
        source: "SCANNER",
        language: "de-CH",
        unread: true,
        updated: 30,
        wantMin: 3.54,
        fitMin: 4,
        sort: "DEADLINE",
        dir: "DESCENDING",
        page: 2,
      }),
    ).toEqual({
      q: "backend",
      // Pipeline order, each once.
      status: ["APPLIED", "OFFER"],
      company: COMPANY,
      source: ["SCANNER"],
      language: "de-ch",
      unread: true,
      updated: 30,
      wantMin: 3.5,
      fitMin: 4,
      sort: "DEADLINE",
      dir: "DESCENDING",
      page: 2,
    });
  });

  it("drops what the user may have typed wrong", () => {
    expect(
      parseApplicationsSearch({
        q: "  ",
        status: ["HIRED"],
        company: "ACME",
        source: [],
        language: "German; drop table",
        unread: "yes",
        updated: 12,
        wantMin: 7,
        fitMin: -1,
        sort: "SALARY",
        dir: "UP",
        page: 0,
      }),
    ).toEqual({});
    expect(parseApplicationsSearch({ dir: "ASCENDING" })).toEqual({});
  });
});

describe("the request for a search", () => {
  it("sends every filter by its API name, in pages of 50", () => {
    const now = new Date(2026, 8, 30, 15, 45);
    expect(
      toSearchParams(
        {
          q: "backend",
          status: ["APPLIED"],
          company: COMPANY,
          source: ["URL"],
          language: "de",
          unread: true,
          updated: 7,
          wantMin: 3,
          fitMin: 4.5,
          sort: "TITLE",
          page: 1,
        },
        now,
      ),
    ).toEqual({
      page: 1,
      size: 50,
      search: "backend",
      status: ["APPLIED"],
      companyId: COMPANY,
      sourceKind: ["URL"],
      language: ["de"],
      unread: true,
      updatedFrom: new Date(2026, 8, 23).toISOString(),
      wantMin: 3,
      fitMin: 4.5,
      sort: "TITLE",
      direction: "ASCENDING",
    });
    expect(toSearchParams({})).toEqual({ page: 0, size: 50 });
  });

  it("starts a time range at local midnight, so it stays the same all day", () => {
    expect(daysAgo(7, new Date(2026, 8, 30, 0, 1))).toBe(daysAgo(7, new Date(2026, 8, 30, 23, 59)));
  });
});

describe("sorting by a column", () => {
  it("shows newest update first by default, and no column while searching by relevance", () => {
    expect(currentOrder({})).toEqual({ sort: "UPDATED", dir: "DESCENDING" });
    expect(currentOrder({ q: "backend" })).toBeNull();
    expect(currentOrder({ q: "backend", sort: "DEADLINE" })).toEqual({ sort: "DEADLINE", dir: "ASCENDING" });
  });

  it("starts a new column in its default direction and flips the same column, back on page one", () => {
    const byDeadline = sortedBy({ page: 3 }, "DEADLINE");
    expect(byDeadline).toEqual({ sort: "DEADLINE" });
    expect(sortedBy(byDeadline, "DEADLINE")).toEqual({ sort: "DEADLINE", dir: "DESCENDING" });
    expect(sortedBy({ sort: "DEADLINE", dir: "DESCENDING" }, "DEADLINE")).toEqual({ sort: "DEADLINE" });
    // The default order is newest update first, so the first press on "Updated" shows the oldest first.
    expect(sortedBy({}, "UPDATED")).toEqual({ sort: "UPDATED", dir: "ASCENDING" });
  });
});

describe("choosing an order (the phone's sort picker)", () => {
  it("keeps a non-default direction, drops the default one and the page, and can go back to the server's order", () => {
    expect(withOrder({ page: 2, unread: true }, { sort: "TITLE", dir: "DESCENDING" })).toEqual({
      unread: true,
      sort: "TITLE",
      dir: "DESCENDING",
    });
    expect(withOrder({}, { sort: "UPDATED", dir: "DESCENDING" })).toEqual({ sort: "UPDATED" });
    expect(withOrder({ q: "x", sort: "TITLE", dir: "DESCENDING" }, null)).toEqual({ q: "x" });
  });
});

describe("changing filters", () => {
  it("sets or clears one filter and goes back to the first page", () => {
    expect(withFilter({ page: 2, sort: "TITLE" }, "status", ["OFFER"])).toEqual({
      sort: "TITLE",
      status: ["OFFER"],
    });
    expect(withFilter({ status: ["OFFER"], unread: true }, "status", [])).toEqual({ unread: true });
    expect(withFilter({ company: COMPANY }, "company", undefined)).toEqual({});
  });

  it("resets every filter but keeps the order, and knows when anything is filtered", () => {
    const search = {
      q: "x",
      status: ["OFFER" as const],
      sort: "TITLE" as const,
      dir: "DESCENDING" as const,
      page: 1,
    };
    expect(withoutFilters(search)).toEqual({ sort: "TITLE", dir: "DESCENDING" });
    expect(isFiltered(search)).toBe(true);
    expect(isFiltered({ sort: "TITLE", page: 2 })).toBe(false);
  });

  it("keeps the board view in the URL, with the filters and order, but without a page", () => {
    expect(parseApplicationsSearch({ view: "board", status: "APPLIED" })).toEqual({
      view: "board",
      status: ["APPLIED"],
    });
    expect(parseApplicationsSearch({ view: "table" })).toEqual({});
    expect(parseApplicationsSearch({ view: "kanban" })).toEqual({});
    const search = { q: "x", sort: "TITLE" as const, page: 2 };
    expect(withView(search, "board")).toEqual({ q: "x", sort: "TITLE", view: "board" });
    expect(withView({ q: "x", view: "board" }, "table")).toEqual({ q: "x" });
    expect(currentView({ view: "board" })).toBe("board");
    expect(currentView({})).toBe("table");
    expect(isFiltered({ view: "board" })).toBe(false);
    expect(withoutFilters({ view: "board", unread: true, sort: "TITLE" })).toEqual({
      view: "board",
      sort: "TITLE",
    });
  });

  it("asks the server for the board's first 200 matches with the same filters and order", () => {
    const now = new Date(2026, 8, 30, 15, 0);
    expect(toBoardSearchParams({ status: ["OFFER"], sort: "TITLE", page: 3, view: "board" }, now)).toEqual({
      page: 0,
      size: 200,
      status: ["OFFER"],
      sort: "TITLE",
      direction: "ASCENDING",
    });
  });

  it("leaves page 0 out of the URL", () => {
    expect(withPage({ page: 2, unread: true }, 0)).toEqual({ unread: true });
    expect(withPage({}, 1)).toEqual({ page: 1 });
  });
});

describe("new application search in the URL", () => {
  it("keeps a valid company to preselect and drops anything else", () => {
    expect(parseNewApplicationSearch({ company: COMPANY, page: 2 })).toEqual({ company: COMPANY });
    expect(parseNewApplicationSearch({ company: "not-an-id" })).toEqual({});
    expect(parseNewApplicationSearch({})).toEqual({});
  });
});
