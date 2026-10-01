// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { CostTotalsResponse } from "../../api/generated/jofi";
import { getLocale } from "../../paraglide/runtime.js";

const PERCENT_FULL = 100;
const MONTH_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/;

/** "2026-09" as "September 2026" in the user's language; the input itself if it is no month. */
export function formatMonth(month: string, locale: string = getLocale()): string {
  const match = MONTH_PATTERN.exec(month);
  if (!match) return month;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, 1));
  // UTC on both sides: the server cuts months at UTC midnight, the label must not slip into the neighbour.
  return new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(date);
}

/**
 * How much of the cap is used, as a whole percent (not clamped: the cap is soft, so spending above it,
 * 250%, is normal and the text must say so; a bar clamps for itself). Rounded down, so a month just below
 * the cap never reads "100%" (that is what the reached state says); no cap left to measure against counts
 * as full as soon as anything is spent.
 */
export function budgetPercent(spentMicros: number, capMicros: number): number {
  if (capMicros <= 0) return spentMicros > 0 ? PERCENT_FULL : 0;
  return Math.max(0, Math.floor((spentMicros / capMicros) * PERCENT_FULL));
}

export interface CostFigure {
  /** The known cost in USD micros, or null when no call of this line has a price (never shown as $0). */
  knownMicros: number | null;
  /** Calls the price table could not price: counted, never costed. */
  unpricedCalls: number;
}

/** What to show as a line's cost: the sum of the priced calls, and how many calls had no price. */
export function costFigure(totals: CostTotalsResponse): CostFigure {
  const allUnpriced = totals.calls > 0 && totals.unknownCostCalls >= totals.calls;
  return {
    knownMicros: allUnpriced ? null : totals.knownCostMicros,
    unpricedCalls: totals.unknownCostCalls,
  };
}
