// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ApiProblemError } from "../../api/fetcher";
import { anApplication } from "../../test/fakeApplicationBackend";
import { applicationFieldErrorsOf, formValues, hasPayBand, toDetailsRequest } from "./application";
import {
  formatDate,
  formatInstant,
  formatMoney,
  formatPayBand,
  formatPercent,
  formatRelative,
  formatScore,
  isLanguageTag,
  languageName,
} from "./format";
import { parseApplicationSearch } from "./tabs";

/** Intl puts no-break spaces between number and currency; compare with plain ones. */
const plain = (text: string) => text.replace(/[  ]/g, " ");

describe("money", () => {
  it("formats exact two-decimal amounts in the user's locale, without .00 for whole amounts", () => {
    expect(formatMoney(65000, "EUR", "en")).toBe("€65,000");
    expect(plain(formatMoney(1234.5, "EUR", "de"))).toBe("1.234,50 €");
    expect(plain(formatMoney(9999999999.99, "CHF", "de"))).toBe("9.999.999.999,99 CHF");
  });

  it("falls back to the plain amount and code for a code Intl refuses", () => {
    expect(formatMoney(10, "E1", "en")).toBe("10 E1");
  });

  it("formats a band with both, one or the other end", () => {
    const band = { currency: "EUR", period: "YEAR", source: "POSTING" } as const;
    expect(plain(formatPayBand({ ...band, min: 60000, max: 75000 }, "de"))).toBe("60.000–75.000 € per year");
    expect(formatPayBand({ ...band, min: 60000, max: null }, "en")).toBe("from €60,000 per year");
    expect(formatPayBand({ ...band, min: null, max: 42.5, period: "HOUR" }, "en")).toBe(
      "up to €42.50 per hour",
    );
  });
});

describe("other formats", () => {
  it("names languages in the user's language, and nothing for unknown or broken tags", () => {
    expect(languageName("de", "en")).toBe("German");
    expect(languageName("fr", "de")).toBe("Französisch");
    expect(languageName("xx", "en")).toBeUndefined();
    expect(languageName("not a tag", "en")).toBeUndefined();
  });

  it("accepts BCP 47 shapes the server accepts", () => {
    expect(isLanguageTag(" de-CH ")).toBe(true);
    expect(isLanguageTag("zh-Hant-TW")).toBe(true);
    expect(isLanguageTag("german")).toBe(false);
    expect(isLanguageTag("d")).toBe(false);
  });

  it("shows a calendar date on the same day in every time zone", () => {
    expect(formatDate("2026-01-01", "en")).toBe("Jan 1, 2026");
    expect(formatDate("2026-10-15", "de")).toBe("15.10.2026");
  });

  it("says how long ago a moment was, in its largest whole unit", () => {
    const now = new Date("2026-09-30T12:00:00Z");
    expect(formatRelative("2026-09-30T11:59:30Z", now, "en")).toBe("now");
    expect(formatRelative("2026-09-30T11:15:00Z", now, "en")).toBe("45 minutes ago");
    expect(formatRelative("2026-09-30T09:00:00Z", now, "de")).toBe("vor 3 Stunden");
    expect(formatRelative("2026-09-29T10:00:00Z", now, "en")).toBe("yesterday");
    expect(formatRelative("2026-09-10T12:00:00Z", now, "en")).toBe("2 weeks ago");
    expect(formatRelative("2025-09-01T12:00:00Z", now, "de")).toBe("letztes Jahr");
  });

  it("shows a moment with date and time", () => {
    expect(plain(formatInstant("2026-09-30T10:05:00Z", "en"))).toMatch(/^Sep 30, 2026, \d{1,2}:05/);
  });

  it("formats percentages and scores", () => {
    expect(plain(formatPercent(40, "de"))).toBe("40 %");
    expect(formatScore(4, "en")).toBe("4.0 / 5");
    expect(formatScore(3.5, "de")).toBe("3,5 / 5");
  });
});

describe("form values", () => {
  it("round-trips an application through the form unchanged", () => {
    const application = anApplication("c1", {
      title: "Engineer",
      location: "Köln",
      remoteShare: 0,
      deadline: "2026-10-15",
      payBand: {
        min: 1,
        max: 2,
        currency: "USD",
        period: "DAY",
        source: "RECRUITER",
        estimateBasis: null,
        estimateConfidence: null,
      },
      languageAndTone: {
        postingLanguage: "en",
        applicationLanguage: "de",
        formOfAddress: "NEUTRAL",
        tone: "PERSONAL",
      },
      offer: { salary: { amount: 3, currency: "USD", period: "MONTH" }, startDate: "2027-01-01" },
    });
    expect(toDetailsRequest(formValues(application))).toEqual({
      title: "Engineer",
      companyId: "c1",
      location: "Köln",
      remoteShare: 0,
      employmentType: null,
      seniority: null,
      deadline: "2026-10-15",
      howApplied: null,
      portalNotes: null,
      payBand: {
        min: 1,
        max: 2,
        currency: "USD",
        period: "DAY",
        source: "RECRUITER",
        estimateBasis: null,
        estimateConfidence: null,
      },
      languageAndTone: {
        postingLanguage: "en",
        applicationLanguage: "de",
        formOfAddress: "NEUTRAL",
        tone: "PERSONAL",
      },
      offer: {
        salary: { amount: 3, currency: "USD", period: "MONTH" },
        bonus: null,
        benefits: null,
        remoteShare: null,
        vacationDays: null,
        noticePeriod: null,
        startDate: "2027-01-01",
        answerBy: null,
      },
    });
  });

  it("sends an estimate without amounts, so the server can say the amount is missing", () => {
    const values = {
      ...formValues(anApplication("c1")),
      paySource: "ESTIMATED" as const,
      payBasis: "A guess",
    };
    expect(hasPayBand(values)).toBe(true);
    expect(toDetailsRequest(values).payBand).toMatchObject({
      min: null,
      max: null,
      estimateBasis: "A guess",
    });
  });

  it("maps nested violations to their fields, with messages for the application rules", () => {
    const error = new ApiProblemError(400, {
      type: "urn:jofi:problem:applications:invalid-application",
      status: 400,
      violations: [
        { field: "payBand.max", problem: "MIN_ABOVE_MAX" },
        { field: "languageAndTone.postingLanguage", problem: "INVALID_LANGUAGE" },
        { field: "offer.salary.currency", problem: "SOMETHING_NEW" },
      ],
    });
    expect(applicationFieldErrorsOf(error)).toEqual({
      "payBand.max": "The maximum cannot be below the minimum.",
      "languageAndTone.postingLanguage": "Enter a language code such as de, en or de-CH.",
      "offer.salary.currency": "This value is not valid.",
    });
  });
});

describe("tabs", () => {
  it("keeps only ready tabs other than the default in the URL", () => {
    expect(parseApplicationSearch({ tab: "overview" })).toEqual({ tab: undefined });
    expect(parseApplicationSearch({ tab: "documents" })).toEqual({ tab: undefined });
    expect(parseApplicationSearch({ tab: 42 })).toEqual({ tab: undefined });
    expect(parseApplicationSearch({ tab: "contacts" })).toEqual({ tab: "contacts" });
    expect(parseApplicationSearch({ tab: "timeline" })).toEqual({ tab: "timeline" });
    expect(parseApplicationSearch({ tab: "description" })).toEqual({ tab: "description" });
  });
});
