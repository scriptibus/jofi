// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { aTask } from "../../test/fakeTaskBackend";
import { currentBucket, describeTiming, formValues, todayIn, toTaskRequest } from "./task";

// Wednesday, 30 September 2026.
const TODAY = "2026-09-30";

describe("currentBucket", () => {
  it("maps a bucket that still means the same on today's calendar", () => {
    expect(currentBucket({ span: "DAY", startsOn: TODAY }, TODAY)).toBe("TODAY");
    expect(currentBucket({ span: "WEEK", startsOn: "2026-09-28" }, TODAY)).toBe("THIS_WEEK");
    expect(currentBucket({ span: "WEEK", startsOn: "2026-10-05" }, TODAY)).toBe("NEXT_WEEK");
    expect(currentBucket({ span: "MONTH", startsOn: "2026-09-01" }, TODAY)).toBe("THIS_MONTH");
    expect(currentBucket({ span: "SOMEDAY" }, TODAY)).toBe("SOMEDAY");
  });

  it("has none for a bucket that has passed, or for an exact time", () => {
    expect(currentBucket({ span: "DAY", startsOn: "2026-09-29" }, TODAY)).toBeUndefined();
    expect(currentBucket({ span: "WEEK", startsOn: "2026-09-21" }, TODAY)).toBeUndefined();
    expect(currentBucket({ span: "MONTH", startsOn: "2026-08-01" }, TODAY)).toBeUndefined();
    expect(
      currentBucket({ dueAt: "2026-10-05T08:00:00Z", localDue: "2026-10-05T10:00:00" }, TODAY),
    ).toBeUndefined();
  });

  it("starts weeks on Monday, also on a Sunday", () => {
    expect(currentBucket({ span: "WEEK", startsOn: "2026-09-28" }, "2026-10-04")).toBe("THIS_WEEK");
  });
});

describe("todayIn", () => {
  it("is the date on the calendar of the zone", () => {
    const instant = new Date("2026-09-30T23:30:00Z");
    expect(todayIn("UTC", instant)).toBe("2026-09-30");
    expect(todayIn("Europe/Berlin", instant)).toBe("2026-10-01");
    expect(todayIn("America/New_York", instant)).toBe("2026-09-30");
  });
});

describe("toTaskRequest", () => {
  const base = { title: " Call Anna ", notes: "  ", linkType: "", linkId: "" } as const;

  it("sends a bucket in the viewer's zone", () => {
    const request = toTaskRequest({ ...base, timing: "TODAY", localDue: "", timeZone: "Asia/Tokyo" });
    expect(request).toEqual({
      title: "Call Anna",
      notes: null,
      timing: { timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone, bucket: "TODAY" },
      link: null,
    });
  });

  it("sends an exact time on the clocks of its zone, with the link", () => {
    const request = toTaskRequest({
      ...base,
      notes: "**Ask** about the offer",
      timing: "EXACT",
      localDue: "2026-10-05T10:00",
      timeZone: "Europe/Berlin",
      linkType: "APPLICATION",
      linkId: "0e3c5a52-0f7e-4c5e-9e0e-2f3b6f7f9a10",
    });
    expect(request.timing).toEqual({ timeZone: "Europe/Berlin", localDue: "2026-10-05T10:00" });
    expect(request.notes).toBe("**Ask** about the offer");
    expect(request.link).toEqual({ type: "APPLICATION", id: "0e3c5a52-0f7e-4c5e-9e0e-2f3b6f7f9a10" });
  });

  it("drops a link kind without a chosen record", () => {
    expect(
      toTaskRequest({ ...base, timing: "SOMEDAY", localDue: "", timeZone: "UTC", linkType: "COMPANY" }).link,
    ).toBeNull();
  });
});

describe("formValues", () => {
  it("starts a new task in this week, in the viewer's zone", () => {
    expect(formValues(undefined, "Europe/Berlin")).toMatchObject({
      timing: "THIS_WEEK",
      timeZone: "Europe/Berlin",
      title: "",
    });
  });

  it("keeps an exact task's own zone and wall-clock time", () => {
    const task = aTask({
      timing: { dueAt: "2026-10-05T09:00:00Z", localDue: "2026-10-05T10:00:00", timeZone: "Europe/London" },
      link: { type: "CONTACT", id: "c0ffee00-0000-4000-8000-000000000000" },
    });
    expect(formValues(task, "Europe/Berlin")).toMatchObject({
      timing: "EXACT",
      localDue: "2026-10-05T10:00",
      timeZone: "Europe/London",
      linkType: "CONTACT",
      linkId: "c0ffee00-0000-4000-8000-000000000000",
    });
  });

  it("asks again for the timing of a bucket that has passed", () => {
    const task = aTask({ timing: { span: "DAY", startsOn: "2000-01-03", endsBefore: "2000-01-04" } });
    expect(formValues(task, "UTC").timing).toBe("");
  });
});

describe("describeTiming", () => {
  it("says when a task is due, in the viewer's zone and locale", () => {
    const exact = {
      dueAt: "2026-10-05T08:00:00Z",
      localDue: "2026-10-05T10:00:00",
      timeZone: "Europe/Berlin",
    };
    expect(describeTiming(exact, "Europe/Berlin", "en-US")).toBe("Due Oct 5, 2026, 10:00 AM");
    expect(describeTiming({ span: "DAY", startsOn: TODAY }, "UTC", "en-US")).toBe("Due Sep 30, 2026");
    expect(describeTiming({ span: "WEEK", startsOn: "2026-09-28" }, "UTC", "en-US")).toBe(
      "Week of Sep 28, 2026",
    );
    expect(describeTiming({ span: "MONTH", startsOn: "2026-10-01" }, "UTC", "en-US")).toBe("October 2026");
    expect(describeTiming({ span: "SOMEDAY" }, "UTC", "en-US")).toBe("Someday");
  });

  it("adds the agreed time when the task was planned in another zone", () => {
    const exact = {
      dueAt: "2026-10-05T08:00:00Z",
      localDue: "2026-10-05T09:00:00",
      timeZone: "Europe/London",
    };
    expect(describeTiming(exact, "Europe/Berlin", "en-US")).toBe(
      "Due Oct 5, 2026, 10:00 AM (Oct 5, 2026, 9:00 AM in Europe/London)",
    );
  });

  it("formats bucket dates as calendar days, whatever the viewer's zone", () => {
    expect(describeTiming({ span: "DAY", startsOn: TODAY }, "Pacific/Honolulu", "de-DE")).toBe(
      "Due 30.09.2026",
    );
  });
});
