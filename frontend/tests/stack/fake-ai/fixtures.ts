// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Fixture files of the fake AI provider (ADR-0036): `fixtures/<task>/<scenario>.json`, where <task> is
// the AiTask name in kebab case (`chat`, `scanner-pre-scoring`, `embedding`) and <scenario> is picked
// by a `[[scenario:<name>]]` marker in the request (default: `default`). The format is documented in
// frontend/AGENTS.md ("Full-stack e2e"). Invalid files stop the fake at startup, naming the file.

import { readdirSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";

export interface ToolCallFixture {
  name: string;
  /** Serialised to the JSON text the provider would send. */
  arguments: Record<string, unknown>;
}

export interface UsageFixture {
  inputTokens: number;
  outputTokens: number;
}

/** One model answer. The n-th answer in a conversation is `turns[n]` (the last one repeats). */
export interface TurnFixture {
  text: string;
  /** Streaming fragments; they must join to `text`. Default: one fragment per word. */
  chunks?: string[];
  toolCalls: ToolCallFixture[];
  finishReason: "stop" | "length" | "content_filter";
  usage?: UsageFixture;
}

/** An HTTP error the provider answers with, to exercise the adapter's error mapping. */
export interface ErrorFixture {
  status: number;
  type: string;
  code?: string;
  message: string;
  retryAfterSeconds?: number;
}

export type Scenario =
  | { kind: "chat"; turns: TurnFixture[] }
  | { kind: "embedding"; dimensions: number }
  | { kind: "error"; error: ErrorFixture };

/** task (kebab case) -> scenario name -> scenario */
export type Fixtures = Map<string, Map<string, Scenario>>;

export class FixtureError extends Error {}

const NAME = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export function loadFixtures(directory: string): Fixtures {
  const fixtures: Fixtures = new Map();
  for (const task of readdirSync(directory, { withFileTypes: true })) {
    if (!task.isDirectory()) continue;
    requireName(task.name, join(directory, task.name));
    const scenarios = new Map<string, Scenario>();
    for (const file of readdirSync(join(directory, task.name))) {
      if (!file.endsWith(".json")) continue;
      const path = join(directory, task.name, file);
      const scenario = basename(file, ".json");
      requireName(scenario, path);
      scenarios.set(scenario, parseScenario(readJson(path), path));
    }
    fixtures.set(task.name, scenarios);
  }
  if (fixtures.size === 0) throw new FixtureError(`No fixtures in ${directory}`);
  return fixtures;
}

function requireName(name: string, path: string) {
  if (!NAME.test(name)) throw new FixtureError(`${path}: names are lower-case kebab case`);
}

function readJson(path: string): unknown {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new FixtureError(`${path}: not valid JSON (${String(error)})`);
  }
}

export function parseScenario(value: unknown, path: string): Scenario {
  const fixture = asObject(value, path);
  string(fixture.description, `${path}: description`);
  const kinds = ["turns", "dimensions", "error"].filter((key) => key in fixture);
  if (kinds.length !== 1) throw new FixtureError(`${path}: needs exactly one of turns, dimensions, error`);
  if ("error" in fixture) return { kind: "error", error: parseError(fixture.error, `${path}: error`) };
  if ("dimensions" in fixture) {
    const dimensions = integer(fixture.dimensions, `${path}: dimensions`);
    if (dimensions < 1 || dimensions > 4096) throw new FixtureError(`${path}: dimensions must be 1..4096`);
    return { kind: "embedding", dimensions };
  }
  const turns = fixture.turns;
  if (!Array.isArray(turns) || turns.length === 0)
    throw new FixtureError(`${path}: turns must be a non-empty array`);
  return { kind: "chat", turns: turns.map((turn, index) => parseTurn(turn, `${path}: turns[${index}]`)) };
}

function parseTurn(value: unknown, path: string): TurnFixture {
  const turn = asObject(value, path);
  const text = turn.text === undefined ? "" : string(turn.text, `${path}.text`);
  const toolCalls = turn.toolCalls === undefined ? [] : parseToolCalls(turn.toolCalls, `${path}.toolCalls`);
  if (text === "" && toolCalls.length === 0) throw new FixtureError(`${path}: needs text or toolCalls`);
  const finishReason = turn.finishReason ?? "stop";
  if (finishReason !== "stop" && finishReason !== "length" && finishReason !== "content_filter") {
    throw new FixtureError(`${path}.finishReason: stop, length or content_filter`);
  }
  const parsed: TurnFixture = { text, toolCalls, finishReason };
  if (turn.chunks !== undefined) {
    const chunks = stringArray(turn.chunks, `${path}.chunks`);
    if (chunks.join("") !== text) throw new FixtureError(`${path}.chunks must join to text`);
    parsed.chunks = chunks;
  }
  if (turn.usage !== undefined) {
    const usage = asObject(turn.usage, `${path}.usage`);
    parsed.usage = {
      inputTokens: integer(usage.inputTokens, `${path}.usage.inputTokens`),
      outputTokens: integer(usage.outputTokens, `${path}.usage.outputTokens`),
    };
  }
  return parsed;
}

function parseToolCalls(value: unknown, path: string): ToolCallFixture[] {
  if (!Array.isArray(value)) throw new FixtureError(`${path} must be an array`);
  return value.map((entry, index) => {
    const call = asObject(entry, `${path}[${index}]`);
    return {
      name: string(call.name, `${path}[${index}].name`),
      arguments: asObject(call.arguments, `${path}[${index}].arguments`),
    };
  });
}

function parseError(value: unknown, path: string): ErrorFixture {
  const error = asObject(value, path);
  const status = integer(error.status, `${path}.status`);
  if (status < 400 || status > 599) throw new FixtureError(`${path}.status must be 400..599`);
  const parsed: ErrorFixture = {
    status,
    type: string(error.type, `${path}.type`),
    message: string(error.message, `${path}.message`),
  };
  if (error.code !== undefined) parsed.code = string(error.code, `${path}.code`);
  if (error.retryAfterSeconds !== undefined) {
    parsed.retryAfterSeconds = integer(error.retryAfterSeconds, `${path}.retryAfterSeconds`);
  }
  return parsed;
}

function asObject(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new FixtureError(`${path} must be an object`);
  }
  return value as Record<string, unknown>;
}

function string(value: unknown, path: string): string {
  if (typeof value !== "string" || value.trim() === "")
    throw new FixtureError(`${path} must be a non-blank string`);
  return value;
}

function stringArray(value: unknown, path: string): string[] {
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== "string")) {
    throw new FixtureError(`${path} must be an array of strings`);
  }
  return value as string[];
}

function integer(value: unknown, path: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    throw new FixtureError(`${path} must be a non-negative integer`);
  }
  return value;
}
