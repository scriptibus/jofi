// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The deterministic fake AI provider for the e2e stack (ADR-0036). It speaks the OpenAI-compatible
// HTTP API (`/v1/chat/completions` with tool calls and SSE streaming, `/v1/embeddings`, `/v1/models`),
// so Jofi reaches it as an ordinary OPENAI_COMPATIBLE provider: the AI gateway, the "never send to AI"
// filter, the Spring AI adapter and the SSRF guard all run unchanged in e2e.
//
// The model name selects the task (`fake-<task>`, e.g. `fake-chat`), a `[[scenario:<name>]]` marker in
// any message selects the scenario, and the number of earlier assistant messages selects the turn.
// Same request, same answer: no randomness, no clock, no delays.

import { createHash } from "node:crypto";
import type { ErrorFixture, Fixtures, Scenario, TurnFixture } from "./fixtures.ts";

export const MODEL_PREFIX = "fake-";
const DEFAULT_SCENARIO = "default";
const SCENARIO_MARKER = /\[\[scenario:([a-z0-9]+(?:-[a-z0-9]+)*)\]\]/;
/** Fixed timestamp in every response, so answers are byte-for-byte reproducible. */
const CREATED = 1_790_000_000;

export interface FakeResponse {
  status: number;
  headers: Record<string, string>;
  /** A JSON body, or server-sent events when `events` is set. */
  body?: unknown;
  events?: string[];
  /** For the log line: task and scenario only, never message content (threat model T4). */
  summary: string;
}

interface ChatMessage {
  role: string;
  content: string;
}

export function handleRequest(
  fixtures: Fixtures,
  method: string,
  path: string,
  rawBody: string,
): FakeResponse {
  const route = `${method} ${path.split("?")[0]}`;
  if (route === "GET /health") return json(200, { status: "UP" }, "health");
  if (route === "GET /v1/models") return json(200, listModels(fixtures), "models");
  if (route === "POST /v1/chat/completions")
    return withBody(rawBody, (body) => chatCompletion(fixtures, body));
  if (route === "POST /v1/embeddings") return withBody(rawBody, (body) => embeddings(fixtures, body));
  return error(404, "invalid_request_error", `Unknown route ${route}`, "unknown route");
}

function listModels(fixtures: Fixtures) {
  const data = [...fixtures.keys()].sort().map((task) => ({
    id: MODEL_PREFIX + task,
    object: "model",
    created: CREATED,
    owned_by: "jofi-e2e",
  }));
  return { object: "list", data };
}

function withBody(rawBody: string, handler: (body: Record<string, unknown>) => FakeResponse): FakeResponse {
  let body: unknown;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return error(400, "invalid_request_error", "The body is not JSON", "bad request");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return error(400, "invalid_request_error", "The body must be a JSON object", "bad request");
  }
  return handler(body as Record<string, unknown>);
}

/** Resolves model -> task -> scenario, or the error response that explains what is missing. */
function resolveScenario(
  fixtures: Fixtures,
  model: unknown,
  texts: string[],
): { task: string; name: string; scenario: Scenario } | FakeResponse {
  const task =
    typeof model === "string" && model.startsWith(MODEL_PREFIX) ? model.slice(MODEL_PREFIX.length) : "";
  const scenarios = fixtures.get(task);
  if (scenarios === undefined) {
    return error(404, "invalid_request_error", `The model ${String(model)} does not exist`, "unknown model", {
      code: "model_not_found",
    });
  }
  const name = texts.map((text) => SCENARIO_MARKER.exec(text)?.[1]).find(Boolean) ?? DEFAULT_SCENARIO;
  const scenario = scenarios.get(name);
  if (scenario === undefined) {
    return error(
      400,
      "invalid_request_error",
      `No fixture ${task}/${name}.json`,
      `task=${task} scenario=${name}`,
    );
  }
  return { task, name, scenario };
}

function chatCompletion(fixtures: Fixtures, body: Record<string, unknown>): FakeResponse {
  const messages = parseMessages(body.messages);
  if (messages === undefined)
    return error(400, "invalid_request_error", "messages must be a non-empty array", "bad");
  const resolved = resolveScenario(
    fixtures,
    body.model,
    messages.map((message) => message.content),
  );
  if ("status" in resolved) return resolved;
  const { task, name, scenario } = resolved;
  const summary = `task=${task} scenario=${name}`;
  if (scenario.kind === "error") return providerError(scenario.error, summary);
  if (scenario.kind !== "chat")
    return error(400, "invalid_request_error", `${task} is not a chat model`, summary);

  const turnIndex = messages.filter((message) => message.role === "assistant").length;
  const turn = scenario.turns[Math.min(turnIndex, scenario.turns.length - 1)] as TurnFixture;
  const offered = toolNames(body.tools);
  const missing = turn.toolCalls.find((call) => !offered.includes(call.name));
  if (missing !== undefined) {
    return error(400, "invalid_request_error", `The fixture calls ${missing.name}, not offered`, summary);
  }
  const id = `chatcmpl-fake-${task}-${name}-${turnIndex}`;
  const usage = turnUsage(turn, messages);
  const stream = body.stream === true;
  const includeUsage = isObject(body.stream_options) && body.stream_options.include_usage === true;
  const model = String(body.model);
  return stream
    ? streamed(id, model, turn, turnIndex, includeUsage ? usage : undefined, `${summary} stream`)
    : json(200, completion(id, model, turn, turnIndex, usage), summary);
}

function parseMessages(value: unknown): ChatMessage[] | undefined {
  if (!Array.isArray(value) || value.length === 0) return undefined;
  return value.map((message) => {
    const entry = isObject(message) ? message : {};
    return { role: String(entry.role), content: contentText(entry.content) };
  });
}

/** Content is a string or a list of parts (`{type: "text", text}`); only text matters here. */
function contentText(content: unknown): string {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((part) => (isObject(part) && typeof part.text === "string" ? part.text : "")).join("");
}

function toolNames(tools: unknown): string[] {
  if (!Array.isArray(tools)) return [];
  return tools.map((tool) => (isObject(tool) && isObject(tool.function) ? String(tool.function.name) : ""));
}

function toolCallsOf(turn: TurnFixture, turnIndex: number) {
  return turn.toolCalls.map((call, index) => ({
    id: `call_${turnIndex}_${index}`,
    type: "function",
    function: { name: call.name, arguments: JSON.stringify(call.arguments) },
  }));
}

function finishReason(turn: TurnFixture): string {
  return turn.toolCalls.length > 0 ? "tool_calls" : turn.finishReason;
}

function completion(id: string, model: string, turn: TurnFixture, turnIndex: number, usage: Usage) {
  const toolCalls = toolCallsOf(turn, turnIndex);
  return {
    id,
    object: "chat.completion",
    created: CREATED,
    model,
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: turn.text === "" ? null : turn.text,
          ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
          refusal: null,
          annotations: [],
        },
        logprobs: null,
        finish_reason: finishReason(turn),
      },
    ],
    usage: openAiUsage(usage),
  };
}

function streamed(
  id: string,
  model: string,
  turn: TurnFixture,
  turnIndex: number,
  usage: Usage | undefined,
  summary: string,
): FakeResponse {
  const chunk = (choices: unknown[], chunkUsage: unknown = null) =>
    JSON.stringify({
      id,
      object: "chat.completion.chunk",
      created: CREATED,
      model,
      choices,
      usage: chunkUsage,
    });
  const delta = (value: Record<string, unknown>, reason: string | null = null) => ({
    index: 0,
    delta: value,
    logprobs: null,
    finish_reason: reason,
  });
  const events = [chunk([delta({ role: "assistant", content: "", refusal: null })])];
  for (const fragment of turn.chunks ?? splitWords(turn.text))
    events.push(chunk([delta({ content: fragment })]));
  toolCallsOf(turn, turnIndex).forEach((call, index) => {
    events.push(chunk([delta({ tool_calls: [{ index, ...call }] })]));
  });
  events.push(chunk([delta({}, finishReason(turn))]));
  if (usage !== undefined) events.push(chunk([], openAiUsage(usage)));
  events.push("[DONE]");
  return {
    status: 200,
    headers: { "content-type": "text/event-stream", "cache-control": "no-cache" },
    events,
    summary,
  };
}

/** Word-sized fragments that join back to the text exactly. */
export function splitWords(text: string): string[] {
  return text === "" ? [] : text.split(/(?<=\s)(?=\S)/);
}

function embeddings(fixtures: Fixtures, body: Record<string, unknown>): FakeResponse {
  const input = typeof body.input === "string" ? [body.input] : body.input;
  if (!Array.isArray(input) || input.length === 0 || input.some((text) => typeof text !== "string")) {
    return error(
      400,
      "invalid_request_error",
      "input must be a string or a non-empty array of strings",
      "bad",
    );
  }
  const texts = input as string[];
  const resolved = resolveScenario(fixtures, body.model, texts);
  if ("status" in resolved) return resolved;
  const { task, name, scenario } = resolved;
  const summary = `task=${task} scenario=${name}`;
  if (scenario.kind === "error") return providerError(scenario.error, summary);
  if (scenario.kind !== "embedding")
    return error(400, "invalid_request_error", `${task} is not an embedding model`, summary);

  const base64 = body.encoding_format === "base64";
  const data = texts.map((text, index) => {
    const vector = embeddingOf(text, scenario.dimensions);
    return { object: "embedding", index, embedding: base64 ? toBase64(vector) : vector };
  });
  const promptTokens = texts.reduce((sum, text) => sum + tokens(text), 0);
  return json(
    200,
    {
      object: "list",
      data,
      model: String(body.model),
      usage: { prompt_tokens: promptTokens, total_tokens: promptTokens },
    },
    summary,
  );
}

/** A unit vector derived from the SHA-256 of the text: equal texts, equal vectors. */
export function embeddingOf(text: string, dimensions: number): number[] {
  const bytes: number[] = [];
  for (let block = 0; bytes.length < dimensions; block++) {
    bytes.push(...createHash("sha256").update(`${block}:${text}`).digest());
  }
  const raw = bytes.slice(0, dimensions).map((byte) => byte / 127.5 - 1);
  const length = Math.hypot(...raw) || 1;
  return raw.map((value) => Math.fround(value / length));
}

/** OpenAI's base64 encoding: little-endian float32. */
function toBase64(vector: number[]): string {
  const buffer = Buffer.alloc(vector.length * 4);
  vector.forEach((value, index) => {
    buffer.writeFloatLE(value, index * 4);
  });
  return buffer.toString("base64");
}

interface Usage {
  inputTokens: number;
  outputTokens: number;
}

function turnUsage(turn: TurnFixture, messages: ChatMessage[]): Usage {
  if (turn.usage !== undefined) return turn.usage;
  const input = messages.reduce((sum, message) => sum + tokens(message.content), 0);
  const toolText = turn.toolCalls.map((call) => call.name + JSON.stringify(call.arguments)).join("");
  return { inputTokens: input, outputTokens: tokens(turn.text + toolText) };
}

/** A deterministic stand-in for a tokenizer: about four characters per token. */
function tokens(text: string): number {
  return Math.ceil(text.length / 4);
}

function openAiUsage(usage: Usage) {
  return {
    prompt_tokens: usage.inputTokens,
    completion_tokens: usage.outputTokens,
    total_tokens: usage.inputTokens + usage.outputTokens,
  };
}

function providerError(fixture: ErrorFixture, summary: string): FakeResponse {
  const response = error(
    fixture.status,
    fixture.type,
    fixture.message,
    `${summary} error=${fixture.status}`,
    {
      code: fixture.code ?? null,
    },
  );
  if (fixture.retryAfterSeconds !== undefined)
    response.headers["retry-after"] = String(fixture.retryAfterSeconds);
  return response;
}

function error(
  status: number,
  type: string,
  message: string,
  summary: string,
  extra: Record<string, unknown> = {},
): FakeResponse {
  return json(status, { error: { message, type, param: null, code: null, ...extra } }, summary);
}

function json(status: number, body: unknown, summary: string): FakeResponse {
  return { status, headers: { "content-type": "application/json" }, body, summary };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
