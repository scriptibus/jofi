// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { formatLocalDateTime } from "./format";
import { describeChangedField } from "./timelineChanges";

const plain = (text: string) => text.replace(/\s/g, " ");

describe("changed fields on the timeline", () => {
  it("names fields without values: free text, ids and whatever the server sends no values for", () => {
    expect(describeChangedField({ field: "title", before: null, after: null })).toEqual({
      field: "title",
      label: "Job title",
    });
    expect(describeChangedField({ field: "company", before: "a-uuid", after: "b-uuid" })).toEqual({
      field: "company",
      label: "Company",
    });
    expect(describeChangedField({ field: "contacts", before: "[]", after: "[x]" }).values).toBeUndefined();
    expect(describeChangedField({ field: "seniority", before: null, after: null }).values).toBeUndefined();
  });

  it("puts values in the user's words", () => {
    expect(
      describeChangedField({ field: "employmentType", before: "FULL_TIME", after: "FREELANCE" }).values,
    ).toEqual({ before: "Full-time", after: "Freelance" });
    expect(describeChangedField({ field: "unread", before: "true", after: "false" }).values).toEqual({
      before: "Unread",
      after: "Read",
    });
    expect(describeChangedField({ field: "deadline", before: null, after: "2026-10-15" }).values).toEqual({
      before: "none",
      after: "Oct 15, 2026",
    });
    expect(describeChangedField({ field: "postingLanguage", before: "de", after: "en" }).values).toEqual({
      before: "German",
      after: "English",
    });
    expect(describeChangedField({ field: "remoteShare", before: "0", after: "100" }).values).toEqual({
      before: "0%",
      after: "100%",
    });
  });

  it("keeps a code it does not know and calls an unknown field other details", () => {
    expect(describeChangedField({ field: "tone", before: "PERSONAL", after: "WARM" }).values).toEqual({
      before: "Personal",
      after: "WARM",
    });
    expect(describeChangedField({ field: "somethingNew", before: "1", after: "2" })).toEqual({
      field: "somethingNew",
      label: "Other details",
    });
  });
});

describe("wall-clock times", () => {
  it("reads the same wherever the user is", () => {
    expect(plain(formatLocalDateTime("2026-10-05T10:00:00", "en"))).toBe("Oct 5, 2026, 10:00 AM");
    expect(formatLocalDateTime("2026-10-05T23:30", "de")).toBe("05.10.2026, 23:30");
  });

  it("leaves a malformed one as it is", () => {
    expect(formatLocalDateTime("soon", "en")).toBe("soon");
  });
});
