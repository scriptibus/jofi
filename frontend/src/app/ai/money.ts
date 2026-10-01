// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { getLocale } from "../../paraglide/runtime.js";

/** Costs and the budget are integer USD micros everywhere in the API (#24). */
export const MICROS_PER_USD = 1_000_000;

/** Below one cent the amount keeps two significant digits, so a cheap month never shows as $0.00. */
const ONE_CENT_MICROS = 10_000;

/** The exact decimal of `micros` as a string (2_345_000 -> "2.345000"), so no float rounding creeps in. */
function exactDecimal(micros: number): `${number}` {
  const whole = Math.floor(Math.abs(micros) / MICROS_PER_USD);
  const fraction = String(Math.abs(micros) % MICROS_PER_USD).padStart(6, "0");
  return `${micros < 0 ? "-" : ""}${whole}.${fraction}` as `${number}`;
}

/**
 * An amount of USD micros in the user's locale ("$2.35" / "2,35 $"). Intl rounds the exact decimal half
 * away from zero to cents; amounts below a cent keep two significant digits.
 */
export function formatUsd(micros: number, locale: string = getLocale()): string {
  const tiny = micros !== 0 && Math.abs(micros) < ONE_CENT_MICROS;
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency: "USD",
    ...(tiny ? { maximumSignificantDigits: 2 } : {}),
  }).format(exactDecimal(micros));
}

/**
 * A price per million tokens in the user's locale, exact to the micro ($0.15, $0.125, $0.000001): prices
 * are small fractions, so cents would hide what the user typed.
 */
export function formatUsdPrice(micros: number, locale: string = getLocale()): string {
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 6,
  }).format(exactDecimal(micros));
}

/** Dollars as entered (a float from the number field) to whole micros. */
export function usdToMicros(usd: number): number {
  return Math.round(usd * MICROS_PER_USD);
}

export function microsToUsd(micros: number): number {
  return micros / MICROS_PER_USD;
}
