// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { PayBandDto, PayDto } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { perPeriodLabels } from "./labels";

// Money comes from the API as exact decimals with two places (e.g. 65000.00, ADR-0041), never as micros.
// A JSON number keeps every such amount up to the server's maximum exactly, so it is formatted as is.

function moneyFormat(currency: string, locale: string): Intl.NumberFormat | undefined {
  try {
    return new Intl.NumberFormat(locale, {
      style: "currency",
      currency,
      trailingZeroDisplay: "stripIfInteger",
    });
  } catch {
    // The server only stores three letters A–Z, but a malformed code must never break the page.
    return undefined;
  }
}

/** An amount in its currency in the user's locale: "€65,000", "65.000 €", "€1,234.50". */
export function formatMoney(amount: number, currency: string, locale: string = getLocale()): string {
  return moneyFormat(currency, locale)?.format(amount) ?? `${amount} ${currency}`;
}

/** "€60,000–75,000 per year", "from €60,000 per year" or "up to …", in the user's locale. */
export function formatPayBand(band: PayBandDto, locale: string = getLocale()): string {
  const { min, max, currency } = band;
  const format = moneyFormat(currency, locale);
  const money = (amount: number) => format?.format(amount) ?? `${amount} ${currency}`;
  let range: string;
  if (min != null && max != null)
    range = format ? format.formatRange(min, max) : `${money(min)}–${money(max)}`;
  else if (min != null) range = m.application_pay_from({ amount: money(min) });
  else range = m.application_pay_up_to({ amount: money(max ?? 0) });
  return m.application_pay_amount({ amount: range, period: perPeriodLabels[band.period]() });
}

/** An offer's salary: "€70,000 per year". */
export function formatPay(pay: PayDto, locale: string = getLocale()): string {
  return m.application_pay_amount({
    amount: formatMoney(pay.amount, pay.currency, locale),
    period: perPeriodLabels[pay.period](),
  });
}

/** The language of a BCP 47 tag in the user's language ("German", "Swiss High German"), if Intl knows it. */
export function languageName(tag: string, locale: string = getLocale()): string | undefined {
  try {
    const name = new Intl.DisplayNames([locale], { type: "language", fallback: "none" }).of(tag);
    return name && name !== tag ? name : undefined;
  } catch {
    return undefined;
  }
}

/** Looks like a BCP 47 language tag (backend `LanguageTag`); the server has the final say. */
export function isLanguageTag(text: string): boolean {
  const tag = text.trim();
  return tag.length <= 35 && /^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$/.test(tag);
}

/** A calendar date from the API (`2026-10-15`, no time zone) as the user reads dates, the same everywhere. */
export function formatDate(isoDate: string, locale: string = getLocale()): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined) return isoDate;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(Date.UTC(year, month - 1, day)),
  );
}

/** A moment from the API (an instant) as a date in the user's time zone. */
export function formatInstantDate(instant: string, locale: string = getLocale()): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(instant));
}

/** A moment from the API with date and time in the user's time zone: "Sep 30, 2026, 10:00 AM". */
export function formatInstant(instant: string, locale: string = getLocale()): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(
    new Date(instant),
  );
}

/**
 * A wall-clock time from the API (`2026-10-05T10:00:00`, no zone) as it reads on that clock, the same
 * wherever the user is: an interview's agreed time in the zone it was planned in (ADR-0048).
 */
export function formatLocalDateTime(localDateTime: string, locale: string = getLocale()): string {
  const [date = "", time = ""] = localDateTime.split("T");
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  if ([year, month, day, hour, minute].some((part) => part === undefined || Number.isNaN(part)))
    return localDateTime;
  const utc = Date.UTC(year ?? 0, (month ?? 1) - 1, day, hour, minute);
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" }).format(
    new Date(utc),
  );
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const RELATIVE_UNITS: readonly [unit: Intl.RelativeTimeFormatUnit, milliseconds: number][] = [
  ["year", 365 * DAY],
  ["month", 30 * DAY],
  ["week", 7 * DAY],
  ["day", DAY],
  ["hour", HOUR],
  ["minute", MINUTE],
];

/** How long ago a moment is, in its largest whole unit: "3 days ago", "vor 2 Stunden", "now". */
export function formatRelative(
  instant: string,
  now: Date = new Date(),
  locale: string = getLocale(),
): string {
  const format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const difference = new Date(instant).getTime() - now.getTime();
  for (const [unit, milliseconds] of RELATIVE_UNITS) {
    if (Math.abs(difference) >= milliseconds)
      return format.format(Math.trunc(difference / milliseconds), unit);
  }
  return format.format(0, "second");
}

/** A percentage 0–100 in the user's locale: "40%", "40 %". */
export function formatPercent(percent: number, locale: string = getLocale()): string {
  return new Intl.NumberFormat(locale, { style: "percent" }).format(percent / 100);
}

/** A score 0–5 with one decimal: "4.5 / 5", "4,5 / 5". */
export function formatScore(score: number, locale: string = getLocale()): string {
  const value = new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(
    score,
  );
  return m.application_score_value({ score: value });
}
