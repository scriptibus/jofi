// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import type { Status } from "./labels";
import { canMoveTo, nextStatuses, takesDeclineReason } from "./statusMatrix";

// ADR-0044's table, copied cell by cell: rows are "from", columns "to", in this order.
const ORDER: Status[] = [
  "DISCOVERED",
  "SHORTLISTED",
  "PREPARING",
  "APPLIED",
  "INTERVIEWING",
  "OFFER",
  "ACCEPTED",
  "REJECTED",
  "WITHDRAWN",
  "DECLINED",
  "GHOSTED",
];
// Columns: DIS SHO PRE APP INT OFF ACC REJ WIT DEC GHO
const MATRIX: Record<Status, string> = {
  DISCOVERED: ". x x x x x . . . x .",
  SHORTLISTED: "x . x x x x . . . x .",
  PREPARING: "x x . x x x . . . x .",
  APPLIED: "x x x . x x . x x . x",
  INTERVIEWING: "x x x x . x . x x . x",
  OFFER: "x x x x x . x x . x x",
  ACCEPTED: "x x x x x x . x . x .",
  REJECTED: "x x x x x x . x . . .",
  WITHDRAWN: "x x x x x x . . . . .",
  DECLINED: "x x x x x x . . . x .",
  GHOSTED: "x x x x x x . x x . .",
};

const cells = Object.entries(MATRIX).flatMap(([from, row]) =>
  row
    .trim()
    .split(/\s+/)
    .map((cell, column) => ({ from: from as Status, to: ORDER[column] as Status, allowed: cell === "x" })),
);

describe("the status transition matrix (ADR-0044)", () => {
  it("covers all 11 × 11 moves", () => {
    expect(cells).toHaveLength(121);
  });

  it.each(cells)("$from → $to allowed: $allowed", ({ from, to, allowed }) => {
    expect(canMoveTo(from, to)).toBe(allowed);
  });

  it("offers the other allowed statuses, pipeline first, never the current one", () => {
    expect(nextStatuses("APPLIED")).toEqual({
      pipeline: ["DISCOVERED", "SHORTLISTED", "PREPARING", "INTERVIEWING", "OFFER"],
      ended: ["REJECTED", "WITHDRAWN", "GHOSTED"],
    });
    expect(nextStatuses("DECLINED")).toEqual({
      pipeline: ["DISCOVERED", "SHORTLISTED", "PREPARING", "APPLIED", "INTERVIEWING", "OFFER"],
      ended: [],
    });
  });

  it("asks for a decline category exactly for Declined and Rejected", () => {
    expect(ORDER.filter(takesDeclineReason)).toEqual(["REJECTED", "DECLINED"]);
  });
});
