// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { aListedApplication } from "../../test/fakeApplicationListBackend";
import {
  APPLICATION_DRAG_TYPE,
  boardDragData,
  canDropOn,
  draggedStatus,
  groupByStatus,
  statusDragType,
  withStatus,
} from "./board";
import type { Status } from "./labels";

const company = crypto.randomUUID();
const typesOf = (data: Record<string, string>) => ({ has: (type: string) => type in data });

describe("board", () => {
  it("groups applications by status, keeping the server's order in each column", () => {
    const first = aListedApplication(company, { title: "First", status: "APPLIED" });
    const second = aListedApplication(company, { title: "Second", status: "APPLIED" });
    const ghosted = aListedApplication(company, { title: "Gone", status: "GHOSTED" });
    const columns = groupByStatus([first, ghosted, second]);
    expect(columns.APPLIED.map((a) => a.title)).toEqual(["First", "Second"]);
    expect(columns.GHOSTED.map((a) => a.title)).toEqual(["Gone"]);
    expect(columns.DISCOVERED).toEqual([]);
    expect(Object.keys(columns)).toHaveLength(11);
  });

  it("carries the id, the status as a type and the title in a drag", () => {
    const application = aListedApplication(company, { title: "Backend", status: "INTERVIEWING" });
    const data = boardDragData(application);
    expect(data).toEqual({
      [APPLICATION_DRAG_TYPE]: application.id,
      "application/x-jofi-status-interviewing": "INTERVIEWING",
      "text/plain": "Backend",
    });
    expect(draggedStatus(typesOf(data))).toBe("INTERVIEWING");
  });

  it("ignores drags that are not one of our cards", () => {
    expect(draggedStatus(typesOf({ "text/plain": "hello" }))).toBeNull();
    expect(draggedStatus(typesOf({ [statusDragType("APPLIED")]: "APPLIED" }))).toBeNull();
    expect(draggedStatus(typesOf({ [APPLICATION_DRAG_TYPE]: "x" }))).toBeNull();
  });

  it.each<[Status, Status, boolean]>([
    ["DISCOVERED", "APPLIED", true],
    ["DISCOVERED", "DECLINED", true],
    ["DISCOVERED", "REJECTED", false],
    ["DISCOVERED", "GHOSTED", false],
    ["APPLIED", "GHOSTED", true],
    ["APPLIED", "DECLINED", false],
    ["OFFER", "ACCEPTED", true],
    ["WITHDRAWN", "INTERVIEWING", true],
    ["WITHDRAWN", "REJECTED", false],
    // The reason correction is the detail page's; on the board a card never drops on its own column.
    ["DECLINED", "DECLINED", false],
    ["APPLIED", "APPLIED", false],
  ])("a card in %s may be dropped on %s: %s", (from, to, allowed) => {
    expect(canDropOn(from, to)).toBe(allowed);
  });

  it("moves one application to another status for the optimistic update", () => {
    const moving = aListedApplication(company, { status: "APPLIED" });
    const staying = aListedApplication(company, { status: "APPLIED" });
    const page = { applications: [moving, staying], page: 0, size: 200, total: 2 };
    const moved = withStatus(page, moving.id, "INTERVIEWING");
    expect(moved?.applications.map((a) => a.status)).toEqual(["INTERVIEWING", "APPLIED"]);
    expect(page.applications[0]?.status).toBe("APPLIED");
    expect(withStatus(undefined, moving.id, "OFFER")).toBeUndefined();
  });
});
