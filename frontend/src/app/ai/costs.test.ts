// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { totals } from "../../test/fakeSetupBackend";
import { budgetPercent, costFigure, formatMonth, formatPercent } from "./costs";

// Intl puts no-break spaces into some German formats.
const plain = (text: string) => text.replace(/\s/g, " ");

describe("formatMonth", () => {
  it.each([
    ["2026-09", "en", "September 2026"],
    ["2026-09", "de", "September 2026"],
    ["2026-01", "de", "Januar 2026"],
    ["2026-12", "en", "December 2026"],
    ["nonsense", "en", "nonsense"],
    ["2026-13", "en", "2026-13"],
  ])("labels month %s in %s as %s, whatever the time zone", (month, locale, expected) => {
    expect(formatMonth(month, locale)).toBe(expected);
  });
});

describe("budgetPercent", () => {
  it.each([
    [0, 10_000_000, 0],
    [3_200_000, 10_000_000, 32],
    [9_999_999, 10_000_000, 99],
    [10_000_000, 10_000_000, 100],
    [12_000_000, 10_000_000, 100],
    [1, 0, 100],
    [0, 0, 0],
  ])("%d micros of a cap of %d is %d percent", (spent, cap, expected) => {
    expect(budgetPercent(spent, cap)).toBe(expected);
  });
});

describe("formatPercent", () => {
  it("follows the locale", () => {
    expect(plain(formatPercent(32, "en"))).toBe("32%");
    expect(plain(formatPercent(32, "de"))).toBe("32 %");
  });
});

describe("costFigure", () => {
  it("never turns a line without any price into a cost of zero", () => {
    expect(costFigure(totals(3, 0, 3))).toEqual({ knownMicros: null, unpricedCalls: 3 });
  });

  it("keeps the sum of the priced calls next to the count of the unpriced ones", () => {
    expect(costFigure(totals(3, 1_200_000, 1))).toEqual({ knownMicros: 1_200_000, unpricedCalls: 1 });
  });

  it("is a real zero for an empty line", () => {
    expect(costFigure(totals(0, 0))).toEqual({ knownMicros: 0, unpricedCalls: 0 });
  });
});
