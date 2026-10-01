// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { model, NEEDS } from "../../test/fakeSetupBackend";
import { checkBaseUrl, sameOrigin } from "./baseUrl";
import { formatUsd, formatUsdPrice, usdToMicros } from "./money";
import { describeSetupError, fieldErrorsOf } from "./setupProblems";
import { describeCapabilities, optionId, parseOptionId, suggestModel } from "./tasks";

// Intl puts a narrow no-break space between amount and currency in German.
const plain = (text: string) => text.replace(/\s/g, " ");

describe("formatUsd", () => {
  it.each([
    [0, "en", "$0.00"],
    [2_345_000, "en", "$2.35"],
    [2_344_999, "en", "$2.34"],
    [1_234_567_890, "en", "$1,234.57"],
    [1_234_567_890, "de", "1.234,57 $"],
    [4_200, "en", "$0.0042"],
    [4_200, "de", "0,0042 $"],
    [-5_000_000, "en", "-$5.00"],
  ])("formats %d micros in %s as %s", (micros, locale, expected) => {
    expect(plain(formatUsd(micros, locale))).toBe(expected);
  });

  it("rounds half away from zero on the exact decimal, not the float", () => {
    // 1.005 is 1.00499999… as a float; the micros are exact.
    expect(formatUsd(1_005_000, "en")).toBe("$1.01");
  });

  it.each([
    [0, "en", "$0.00"],
    [150_000, "en", "$0.15"],
    [125, "en", "$0.000125"],
    [1, "en", "$0.000001"],
    [10_000_000_000, "en", "$10,000.00"],
    [125_000, "de", "0,125 $"],
  ])("formats the price %d micros in %s as %s, exact to the micro", (micros, locale, expected) => {
    expect(plain(formatUsdPrice(micros, locale))).toBe(expected);
  });

  it("turns dollars into whole micros", () => {
    expect(usdToMicros(12.34)).toBe(12_340_000);
    expect(usdToMicros(0.1 + 0.2)).toBe(300_000);
  });
});

describe("checkBaseUrl", () => {
  it.each([
    ["", "empty"],
    ["not a url", "invalid"],
    ["ftp://example.com", "invalid"],
    ["https://user:pw@example.com/v1", "credentials"],
    ["https://example.com/v1?key=abc", "credentials"],
    ["https://example.com/v1?", "credentials"],
    ["https://example.com/v1#x", "credentials"],
    [`https://example.com/${"a".repeat(2_000)}`, "too-long"],
  ])("refuses %s as %s", (url, status) => {
    expect(checkBaseUrl(url).status).toBe(status);
  });

  it.each([
    ["https://openrouter.ai/api/v1", false],
    ["http://localhost:11434/v1", false],
    ["http://127.0.0.1:1234/v1", false],
    ["http://[::1]:8080/v1", false],
    ["http://ollama.lan:11434/v1", true],
    ["http://fake-ai:8080/v1", true],
  ])("accepts %s, insecure: %s", (url, insecure) => {
    expect(checkBaseUrl(url)).toEqual({ status: "ok", insecure });
  });

  it("compares origins like the server: scheme, host and port", () => {
    expect(sameOrigin("http://fake-ai:8080/v1", "http://FAKE-AI:8080/v2")).toBe(true);
    expect(sameOrigin("https://example.com/v1", "https://example.com:443/other")).toBe(true);
    expect(sameOrigin("https://example.com/v1", "http://example.com/v1")).toBe(false);
    expect(sameOrigin("http://fake-ai:8080/v1", "http://127.0.0.1:8080/v1")).toBe(false);
  });
});

describe("setup problems", () => {
  const problem = (status: number, type: string, extra: Record<string, unknown> = {}) =>
    new ApiProblemError(status, { status, type, ...extra });

  it("says why a connection test failed", () => {
    expect(
      describeSetupError(problem(502, "urn:jofi:problem:setup:provider-authentication-failed")).message,
    ).toMatch(/refused the API key/);
    expect(describeSetupError(problem(409, "urn:jofi:problem:setup:provider-in-use")).message).toMatch(
      /Tasks still use this provider/,
    );
    expect(
      describeSetupError(
        new ConfirmationMismatchError({ operation: "a", targets: [] }, { operation: "b", targets: [] }),
      ).message,
    ).toMatch(/nothing was done/);
  });

  it("puts violations at their fields; a missing key on an update means the origin changed", () => {
    const error = problem(400, "urn:jofi:problem:setup:invalid-input", {
      violations: [
        { field: "apiKey", problem: "REQUIRED" },
        { field: "baseUrl", problem: "INVALID_URL" },
        { field: "displayName", problem: "SOMETHING_NEW" },
      ],
    });
    expect(fieldErrorsOf(error, true)).toEqual({
      apiKey: expect.stringMatching(/points to another server. Enter the API key again/),
      baseUrl: expect.stringMatching(/full web address/),
      displayName: "Not accepted (SOMETHING_NEW).",
    });
    expect(fieldErrorsOf(error).apiKey).toBe("Enter the API key.");
    expect(fieldErrorsOf(problem(502, "urn:jofi:problem:setup:provider-rejected"))).toEqual({});
  });
});

describe("tasks", () => {
  const candidates = [
    { providerId: "p", model: model("big-model", ["TOOL_USE", "STREAMING"], 200_000) },
    { providerId: "p", model: model("gpt-mini", ["TOOL_USE", "STREAMING"], 128_000) },
    { providerId: "p", model: model("text-embed", ["EMBEDDING"]) },
    { providerId: "p", model: model("tiny-local", [], 4_096) },
  ];

  it("suggests a small model for cheap tasks and a strong one for the rest, if it fits", () => {
    expect(suggestModel("CLASSIFICATION", NEEDS.CLASSIFICATION, candidates)?.model.model).toBe("gpt-mini");
    expect(suggestModel("CHAT", NEEDS.CHAT, candidates)?.model.model).toBe("big-model");
    expect(suggestModel("EMBEDDING", NEEDS.EMBEDDING, candidates)?.model.model).toBe("text-embed");
    expect(suggestModel("SPEECH_TO_TEXT", NEEDS.SPEECH_TO_TEXT, candidates)).toBeUndefined();
  });

  it("names what a model lacks", () => {
    expect(
      describeCapabilities({ features: ["TOOL_USE", "STREAMING"], minContextWindowTokens: 32_768 }),
    ).toEqual(["tool use", "streaming", "a context of at least 32,768 tokens"]);
  });

  it("keeps model names with any characters in option ids", () => {
    const id = optionId("p-1", 'meta/llama3:8b "q4"');
    expect(parseOptionId(id)).toEqual({ providerId: "p-1", model: 'meta/llama3:8b "q4"' });
    expect(parseOptionId("garbage")).toBeUndefined();
  });
});
