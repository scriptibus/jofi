// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ApiProblemError } from "../../../api/fetcher";
import { PostingImportResponseFailure } from "../../../api/generated/jofi";
import {
  describeStartError,
  draftFromShare,
  failureMessage,
  isRetryable,
  needsAiSetup,
  offersPasting,
  startErrorMessage,
  violationMessage,
  wasAlreadyImported,
} from "./importModel";

const applicationProblem = (status: number, code: string, violations?: unknown) =>
  new ApiProblemError(status, {
    type: `urn:jofi:problem:applications:${code}`,
    status,
    ...(violations ? { violations } : {}),
  } as never);

describe("describeStartError", () => {
  it("reads the violation of a link and of a text", () => {
    const link = applicationProblem(400, "invalid-application", [
      { field: "originalUrl", problem: "TIMEOUT" },
    ]);
    const text = applicationProblem(400, "invalid-application", [
      { field: "description", problem: "TOO_LONG" },
    ]);

    expect(describeStartError(link)).toEqual({
      kind: "violation",
      violation: { source: "url", problem: "TIMEOUT" },
    });
    expect(describeStartError(text)).toEqual({
      kind: "violation",
      violation: { source: "text", problem: "TOO_LONG" },
    });
  });

  it("tells the two 409 answers apart", () => {
    expect(describeStartError(applicationProblem(409, "ai-not-configured"))).toEqual({
      kind: "ai-not-configured",
    });
    expect(describeStartError(applicationProblem(409, "import-in-progress"))).toEqual({
      kind: "in-progress",
    });
  });

  it("describes anything else generically, an unreadable violation list included", () => {
    expect(describeStartError(new TypeError("offline"))).toMatchObject({
      kind: "other",
      failure: { message: expect.stringContaining("reach") },
    });
    expect(describeStartError(applicationProblem(400, "invalid-application", "nonsense")).kind).toBe("other");
    expect(
      describeStartError(
        applicationProblem(400, "invalid-application", [{ field: "title", problem: "REQUIRED" }]),
      ).kind,
    ).toBe("other");
  });
});

describe("violationMessage", () => {
  it.each([
    ["url", "REQUIRED", "Enter the link"],
    ["url", "INVALID_URL", "not a link Jofi can open"],
    ["url", "NOT_ALLOWED", "LinkedIn, StepStone or Indeed"],
    ["url", "UNREACHABLE", "could not reach"],
    ["url", "TIMEOUT", "too long to answer"],
    ["url", "TOO_LARGE", "too large"],
    ["url", "NOT_HTML", "does not lead to a web page"],
    ["url", "LOGIN_REQUIRED", "behind a login"],
    ["url", "NO_TEXT", "no readable text"],
    ["text", "REQUIRED", "Paste the text"],
    ["text", "TOO_LONG", "100,000 characters"],
    ["text", "INVALID_CHARACTER", "cannot store"],
  ] as const)("%s %s", (source, problem, fragment) => {
    expect(violationMessage({ source, problem })).toContain(fragment);
  });

  it("falls back to a generic message for a code it does not know, never a property of Object", () => {
    expect(violationMessage({ source: "url", problem: "SOMETHING_NEW" })).toContain("could not use this");
    expect(violationMessage({ source: "url", problem: "constructor" })).toContain("could not use this");
  });

  it("offers the text for every link problem pasting gets around, and for nothing else", () => {
    for (const problem of [
      "NOT_ALLOWED",
      "UNREACHABLE",
      "TIMEOUT",
      "TOO_LARGE",
      "NOT_HTML",
      "LOGIN_REQUIRED",
      "NO_TEXT",
    ])
      expect(offersPasting({ source: "url", problem })).toBe(true);
    expect(offersPasting({ source: "url", problem: "INVALID_URL" })).toBe(false);
    expect(offersPasting({ source: "url", problem: "REQUIRED" })).toBe(false);
    expect(offersPasting({ source: "text", problem: "TOO_LONG" })).toBe(false);
  });
});

describe("startErrorMessage", () => {
  it("gives every kind a message", () => {
    expect(startErrorMessage({ kind: "ai-not-configured" })).toContain("No AI model");
    expect(startErrorMessage({ kind: "in-progress" })).toContain("right now");
    expect(startErrorMessage({ kind: "other", failure: { message: "Offline" } })).toBe("Offline");
    expect(
      startErrorMessage({ kind: "violation", violation: { source: "url", problem: "TIMEOUT" } }),
    ).toContain("too long");
  });
});

describe("failures of a finished import", () => {
  it("has a message for every failure the backend names, and a generic one for none", () => {
    for (const failure of Object.values(PostingImportResponseFailure))
      expect(failureMessage(failure)).not.toBe("");
    expect(failureMessage(null)).toContain("did not finish");
  });

  it("retries only what a second try can fix", () => {
    expect(Object.values(PostingImportResponseFailure).filter(isRetryable)).toEqual([
      "AI_UNAVAILABLE",
      "UNREADABLE_ANSWER",
      "NOT_QUEUED",
      "NOT_COMPLETED",
    ]);
    expect(isRetryable(null)).toBe(false);
  });

  it("sends the AI failures that the user fixes to the AI settings", () => {
    expect(Object.values(PostingImportResponseFailure).filter(needsAiSetup)).toEqual([
      "AI_NOT_CONFIGURED",
      "AI_AUTHENTICATION_FAILED",
      "AI_REJECTED",
    ]);
  });
});

describe("wasAlreadyImported", () => {
  const base = { id: "i", attempt: 1, createdAt: "", updatedAt: "", failure: null, applicationId: null };

  it("is true only for an import that is done the moment it is started", () => {
    expect(wasAlreadyImported({ ...base, status: "SUCCEEDED", applicationId: "a" })).toBe(true);
    expect(wasAlreadyImported({ ...base, status: "PENDING" })).toBe(false);
  });
});

describe("draftFromShare", () => {
  it("imports the link a share sheet puts into url", () => {
    expect(draftFromShare({ title: "Kotlin Developer", url: "https://jobs.example/42" })).toMatchObject({
      source: "url",
      url: "https://jobs.example/42",
    });
  });

  it("finds the link in the text, as Android shares it, without its trailing punctuation", () => {
    expect(draftFromShare({ text: "Look at this job: https://jobs.example/42?ref=a." })).toMatchObject({
      source: "url",
      url: "https://jobs.example/42?ref=a",
    });
  });

  it("takes a link in url before a link in the text", () => {
    expect(draftFromShare({ url: "https://a.example/1", text: "see https://b.example/2" })).toMatchObject({
      url: "https://a.example/1",
    });
  });

  it("imports title and text as text when there is no link", () => {
    expect(draftFromShare({ title: "Kotlin Developer", text: "ACME GmbH, Berlin" })).toMatchObject({
      source: "text",
      text: "Kotlin Developer\n\nACME GmbH, Berlin",
    });
  });

  it.each([
    ["javascript:alert(1)"],
    ["file:///etc/passwd"],
    ["data:text/html,<script>1</script>"],
    ["not a link"],
  ])("never takes %s for a link", (url) => {
    const draft = draftFromShare({ url, title: "Job" });
    expect(draft.source).toBe("text");
    expect(draft.url).toBe("");
  });
});
