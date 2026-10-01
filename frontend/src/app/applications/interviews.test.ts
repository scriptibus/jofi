// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ApiProblemError } from "../../api/fetcher";
import { anInterview, instantOf } from "../../test/fakeInterviewBackend";
import { formatLocalDateTime } from "./format";
import {
  describeInterviewDelete,
  describeInterviewError,
  interviewFieldErrors,
  interviewFormValues,
  timeOnUserClock,
  timeZoneChoices,
  toInterviewRequest,
} from "./interviews";
import { parseApplicationSearch } from "./tabs";

const effect = (name: string) => ({ kind: "interview", name, counts: {} });

describe("interview helpers", () => {
  it("starts a new interview as a phone screen in the given zone, an edit with every stored field", () => {
    expect(interviewFormValues(undefined, "Asia/Tokyo")).toEqual({
      type: "PHONE_SCREEN",
      localStart: null,
      timeZone: "Asia/Tokyo",
      participantIds: [],
      preparationNotes: "",
      notes: "",
      outcome: null,
    });
    const stored = anInterview("app", { notes: "Went well", outcome: "PASSED", participantIds: ["c1"] });
    expect(interviewFormValues(stored)).toMatchObject({
      localStart: stored.localStart,
      timeZone: "Europe/Berlin",
      participantIds: ["c1"],
      preparationNotes: "",
      notes: "Went well",
      outcome: "PASSED",
    });
  });

  it("sends blank notes as absent and keeps the wall-clock time and zone as entered", () => {
    expect(
      toInterviewRequest({
        type: "HR",
        localStart: "2026-10-05T10:00",
        timeZone: "America/New_York",
        participantIds: ["c1"],
        preparationNotes: "  ",
        notes: "Ask about the team",
        outcome: null,
      }),
    ).toEqual({
      type: "HR",
      localStart: "2026-10-05T10:00",
      timeZone: "America/New_York",
      participantIds: ["c1"],
      preparationNotes: null,
      notes: "Ask about the team",
      outcome: null,
    });
  });

  it("offers every known zone, UTC and an unknown stored one, sorted", () => {
    const zones = timeZoneChoices("UTC+05:30");
    expect(zones).toContain("Europe/Berlin");
    expect(zones).toContain("UTC");
    expect(zones).toContain("UTC+05:30");
    expect(zones).toEqual([...zones].sort((a, b) => a.localeCompare(b)));
    expect(new Set(zones).size).toBe(zones.length);
  });

  it("shows the user's own clock only when it reads differently from the agreed time", () => {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const here = { localStart: "2026-10-05T10:00", startsAt: instantOf("2026-10-05T10:00", zone) };
    expect(timeOnUserClock(here, "en-US")).toBeUndefined();
    const kiritimati = {
      localStart: "2026-10-05T10:00",
      startsAt: instantOf("2026-10-05T10:00", "Pacific/Kiritimati"),
    };
    expect(timeOnUserClock(kiritimati, "en-US")).toBeDefined();
  });

  it("asks about the delete in words from the server's effect, and falls back to its name", () => {
    expect(describeInterviewDelete(effect("PHONE_SCREEN 2026-10-06T14:30 Europe/Berlin"))).toBe(
      `Phone screen on ${formatLocalDateTime("2026-10-06T14:30")} (Europe/Berlin) will be deleted with its notes. This cannot be undone.`,
    );
    expect(describeInterviewDelete(effect("SOMETHING_NEW 2026-10-06T14:30 +02:00"))).toBe(
      "SOMETHING_NEW 2026-10-06T14:30 +02:00 will be deleted with its notes. This cannot be undone.",
    );
  });

  it("maps the interview problems and field violations", () => {
    const gone = new ApiProblemError(404, {
      type: "urn:jofi:problem:applications:interview-not-found",
      status: 404,
    });
    expect(describeInterviewError(gone).message).toMatch(/no longer exists/);
    const invalid = new ApiProblemError(400, {
      type: "urn:jofi:problem:applications:invalid-application",
      status: 400,
      violations: [
        { field: "timeZone", problem: "INVALID_TIME_ZONE" },
        { field: "localStart", problem: "OUT_OF_RANGE" },
        { field: "notes", problem: "TOO_LONG" },
      ],
    });
    expect(interviewFieldErrors(invalid)).toEqual({
      timeZone: "Jofi does not know this time zone. Choose another one.",
      localStart: "Choose a time between the years 2000 and 2099.",
      notes: "This is too long.",
    });
  });

  it("opens the Interviews tab from the address", () => {
    expect(parseApplicationSearch({ tab: "interviews" })).toEqual({ tab: "interviews" });
  });
});
