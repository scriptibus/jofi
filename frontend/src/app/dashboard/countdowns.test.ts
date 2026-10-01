// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { afterEach, describe, expect, it } from "vitest";
import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { getLocale, overwriteGetLocale } from "../../paraglide/runtime.js";
import { aDashboardCountdown } from "../../test/fakeCountdownBackend";
import {
  daysBetween,
  describeCountdownError,
  describeRemaining,
  describeTarget,
  sourceLabels,
  targetDay,
} from "./countdowns";

const originalLocale = getLocale;
afterEach(() => overwriteGetLocale(originalLocale));

/** The next interview, 10:00 in Berlin on 5 October 2026 (08:00 UTC). */
const interview = aDashboardCountdown({
  source: "NEXT_INTERVIEW",
  title: "Backend Engineer",
  targetDate: null,
  targetAt: "2026-10-05T08:00:00Z",
  localTarget: "2026-10-05T10:00:00",
  timeZone: "Europe/Berlin",
  subjectType: "interview",
});

describe("daysBetween", () => {
  it("counts whole calendar days, also across months, years and leap days", () => {
    expect(daysBetween("2026-09-30", "2026-09-30")).toBe(0);
    expect(daysBetween("2026-09-30", "2026-10-01")).toBe(1);
    expect(daysBetween("2026-12-31", "2027-01-01")).toBe(1);
    expect(daysBetween("2028-02-28", "2028-03-01")).toBe(2);
    expect(daysBetween("2026-09-30", "2026-09-27")).toBe(-3);
  });

  it("is not thrown off by a daylight saving change (dates, not local midnights)", () => {
    // Europe switches back on 25 October 2026: that day has 25 hours locally.
    expect(daysBetween("2026-10-24", "2026-10-26")).toBe(2);
    expect(daysBetween("2026-03-28", "2026-03-30")).toBe(2);
  });
});

describe("targetDay", () => {
  it("takes a date as it is, whatever the viewer's zone", () => {
    const deadline = aDashboardCountdown({ targetDate: "2026-10-31" });
    expect(targetDay(deadline, "Pacific/Auckland")).toBe("2026-10-31");
    expect(targetDay(deadline, "America/Los_Angeles")).toBe("2026-10-31");
  });

  it("puts an instant on the viewer's calendar", () => {
    const late = { ...interview, targetAt: "2026-10-05T23:30:00Z" };
    expect(targetDay(late, "UTC")).toBe("2026-10-05");
    // 01:30 the next morning in Berlin, 08:30 in Tokyo, still the evening before in New York.
    expect(targetDay(late, "Europe/Berlin")).toBe("2026-10-06");
    expect(targetDay(late, "Asia/Tokyo")).toBe("2026-10-06");
    expect(targetDay(late, "America/New_York")).toBe("2026-10-05");
  });

  it("has none without a target", () => {
    expect(targetDay(aDashboardCountdown({ targetDate: null, targetAt: null }), "UTC")).toBeUndefined();
  });
});

describe("describeRemaining", () => {
  it("says today, tomorrow, in n days, and reached once the day has passed", () => {
    overwriteGetLocale(() => "en");
    expect(describeRemaining(0)).toBe("Today");
    expect(describeRemaining(1)).toBe("Tomorrow");
    expect(describeRemaining(2)).toBe("In 2 days");
    expect(describeRemaining(365)).toBe("In 365 days");
    expect(describeRemaining(-1)).toBe("Reached");
    expect(describeRemaining(-400)).toBe("Reached");
  });

  it("speaks German", () => {
    overwriteGetLocale(() => "de");
    expect(describeRemaining(0)).toBe("Heute");
    expect(describeRemaining(1)).toBe("Morgen");
    expect(describeRemaining(12)).toBe("In 12 Tagen");
    expect(describeRemaining(-3)).toBe("Erreicht");
  });
});

describe("describeTarget", () => {
  it("formats a date in the user's locale, without shifting it by a zone", () => {
    const deadline = aDashboardCountdown({ targetDate: "2026-10-31" });
    expect(describeTarget(deadline, "America/Los_Angeles", "en")).toBe("Oct 31, 2026");
    expect(describeTarget(deadline, "Pacific/Auckland", "de")).toBe("31.10.2026");
  });

  it("shows an instant in the viewer's zone", () => {
    expect(describeTarget(interview, "Europe/Berlin", "de")).toBe("05.10.2026, 10:00");
    expect(describeTarget(interview, "Europe/Berlin", "en")).toMatch(/^Oct 5, 2026, 10:00\sAM$/);
  });

  it("adds the agreed time when the viewer is in another zone", () => {
    overwriteGetLocale(() => "de");
    expect(describeTarget(interview, "Europe/London", "de")).toBe(
      "05.10.2026, 09:00 (05.10.2026, 10:00 in Europe/Berlin)",
    );
  });

  it("falls back to the browser's zone for one it does not know", () => {
    expect(describeTarget(interview, "Mars/Olympus_Mons", "de")).toMatch(/^05\.10\.2026, \d{2}:00/);
  });

  it("is empty without a target", () => {
    expect(describeTarget(aDashboardCountdown({ targetDate: null, targetAt: null }), "UTC")).toBe("");
  });
});

describe("sourceLabels", () => {
  it("names every kind of countdown", () => {
    overwriteGetLocale(() => "en");
    expect(Object.fromEntries(Object.entries(sourceLabels).map(([kind, label]) => [kind, label()]))).toEqual({
      CUSTOM: "Your countdown",
      NEXT_INTERVIEW: "Next interview",
      APPLICATION_DEADLINE: "Application deadline",
      OFFER_ANSWER_DEADLINE: "Offer answer deadline",
    });
  });
});

describe("describeCountdownError", () => {
  it("explains a countdown that is gone and a confirmation for something else", () => {
    overwriteGetLocale(() => "en");
    const gone = new ApiProblemError(404, {
      type: "urn:jofi:problem:tasks:countdown-not-found",
      status: 404,
    });
    expect(describeCountdownError(gone).message).toBe("This countdown does not exist (any more).");
    expect(
      describeCountdownError(
        new ConfirmationMismatchError(
          { operation: "countdowns.delete", targets: ["c1"] },
          { operation: "tasks.delete", targets: ["c1"] },
        ),
      ).message,
    ).toMatch(/^The server asked to confirm something other than deleting this countdown\./);
  });
});
