// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Tests for the fake AI provider: protocol shape, determinism, tool calls, streaming, errors, the
// fixture format, and that the server refuses to start outside the e2e profile.
// Run: pnpm test:stack   (node --test; no browser, no containers)

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtempSync, writeFileSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, test } from "node:test";
import { fileURLToPath } from "node:url";
import { embeddingOf, handleRequest, splitWords } from "./fake-ai.ts";
import { FixtureError, loadFixtures, parseScenario } from "./fixtures.ts";

const fixtureDirectory = fileURLToPath(new URL("./fixtures", import.meta.url));
const serverPath = fileURLToPath(new URL("./server.ts", import.meta.url));
const fixtures = loadFixtures(fixtureDirectory);

// biome-ignore lint/suspicious/noExplicitAny: test helper reads loosely typed JSON answers
type Json = any;

function post(path: string, body: unknown) {
  return handleRequest(fixtures, "POST", path, JSON.stringify(body));
}

function chat(messages: unknown[], extra: Record<string, unknown> = {}) {
  return post("/v1/chat/completions", { model: "fake-chat", messages, ...extra });
}

const findCompany = { type: "function", function: { name: "find_company", parameters: { type: "object" } } };

describe("fixtures", () => {
  test("every AI text task and embeddings have a default scenario", () => {
    const tasks = [
      "scanner-pre-scoring",
      "classification",
      "language-tone-detection",
      "extraction",
      "knowledge-interview",
      "document-generation",
      "interview-training",
      "chat",
      "embedding",
    ];
    for (const task of tasks) assert.ok(fixtures.get(task)?.has("default"), `${task}/default.json`);
  });

  test("invalid fixtures are rejected with the file name", () => {
    assert.throws(() => parseScenario({ turns: [] }, "x.json"), /x\.json: description/);
    assert.throws(
      () => parseScenario({ description: "d", turns: [{}] }, "x.json"),
      /needs text or toolCalls/,
    );
    assert.throws(
      () => parseScenario({ description: "d", turns: [{ text: "a b", chunks: ["a"] }] }, "x.json"),
      /chunks must join to text/,
    );
    assert.throws(
      () => parseScenario({ description: "d", dimensions: 3, error: { status: 500 } }, "x.json"),
      /exactly one of/,
    );
    assert.throws(() => parseScenario({ description: "d", error: { status: 200 } }, "x.json"), FixtureError);
  });

  test("an empty fixture directory stops the fake", () => {
    assert.throws(() => loadFixtures(mkdtempSync(join(tmpdir(), "fake-ai-"))), /No fixtures/);
  });
});

describe("chat completions", () => {
  test("the same request gets the same answer, with usage", () => {
    const first = chat([{ role: "user", content: "Hello" }]);
    const second = chat([{ role: "user", content: "Hello" }]);
    assert.equal(first.status, 200);
    assert.deepEqual(first, second);
    const body: Json = first.body;
    assert.equal(body.object, "chat.completion");
    assert.equal(body.model, "fake-chat");
    assert.match(body.choices[0].message.content, /same answer/);
    assert.equal(body.choices[0].finish_reason, "stop");
    assert.deepEqual(body.usage, { prompt_tokens: 2, completion_tokens: 21, total_tokens: 23 });
  });

  test("the model name selects the task", () => {
    const body: Json = post("/v1/chat/completions", {
      model: "fake-extraction",
      messages: [{ role: "user", content: "posting" }],
    }).body;
    assert.match(body.choices[0].message.content, /Senior Kotlin Developer/);
  });

  test("a scenario marker in any message, also in content parts, selects the scenario", () => {
    const body: Json = chat([
      { role: "system", content: "You are Jofi." },
      { role: "user", content: [{ type: "text", text: "Cut short [[scenario:truncated]]" }] },
    ]).body;
    assert.equal(body.choices[0].finish_reason, "length");
  });

  test("tool calls come first, the answer follows the tool result", () => {
    const question = { role: "user", content: "Who is ACME? [[scenario:tool-call]]" };
    const first: Json = chat([question], { tools: [findCompany] }).body;
    assert.equal(first.choices[0].finish_reason, "tool_calls");
    assert.equal(first.choices[0].message.content, null);
    assert.deepEqual(first.choices[0].message.tool_calls, [
      {
        id: "call_0_0",
        type: "function",
        function: { name: "find_company", arguments: '{"name":"ACME GmbH"}' },
      },
    ]);

    const second: Json = chat(
      [
        question,
        { role: "assistant", content: null, tool_calls: first.choices[0].message.tool_calls },
        { role: "tool", tool_call_id: "call_0_0", content: '{"name":"ACME GmbH"}' },
      ],
      { tools: [findCompany] },
    ).body;
    assert.equal(second.choices[0].finish_reason, "stop");
    assert.match(second.choices[0].message.content, /demo company/);
  });

  test("a tool call the request does not offer is a loud 400", () => {
    const response = chat([{ role: "user", content: "[[scenario:tool-call]]" }]);
    assert.equal(response.status, 400);
    assert.match((response.body as Json).error.message, /find_company/);
  });

  test("streaming sends word deltas, tool calls, the finish reason, usage and [DONE]", () => {
    const response = chat([{ role: "user", content: "Hello" }], {
      stream: true,
      stream_options: { include_usage: true },
    });
    assert.equal(response.headers["content-type"], "text/event-stream");
    const events = response.events ?? [];
    assert.equal(events.at(-1), "[DONE]");
    const chunks: Json[] = events.slice(0, -1).map((event) => JSON.parse(event));
    const text = chunks.map((chunk) => chunk.choices[0]?.delta.content ?? "").join("");
    assert.match(text, /^Hi, I am Jofi's fake AI\./);
    assert.ok(chunks.length > 5, "one delta per word");
    assert.equal(chunks.at(-2).choices[0].finish_reason, "stop");
    assert.deepEqual(chunks.at(-1).choices, []);
    assert.equal(chunks.at(-1).usage.total_tokens, 23);

    const tools = chat([{ role: "user", content: "[[scenario:tool-call]]" }], {
      stream: true,
      tools: [findCompany],
    });
    const toolChunks: Json[] = (tools.events ?? []).slice(0, -1).map((event) => JSON.parse(event));
    assert.equal(toolChunks[1].choices[0].delta.tool_calls[0].function.name, "find_company");
    assert.equal(toolChunks.at(-1).choices[0].finish_reason, "tool_calls");
  });

  test("error scenarios answer like the provider, with Retry-After", () => {
    const limited = chat([{ role: "user", content: "[[scenario:rate-limited]]" }]);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers["retry-after"], "2");
    assert.equal((limited.body as Json).error.code, "rate_limit_exceeded");
    assert.equal(chat([{ role: "user", content: "[[scenario:unavailable]]" }]).status, 503);
    assert.equal(chat([{ role: "user", content: "[[scenario:auth-failed]]" }]).status, 401);
  });

  test("unknown models, scenarios and bodies are loud errors", () => {
    const unknownModel = post("/v1/chat/completions", {
      model: "gpt-4o",
      messages: [{ role: "user", content: "x" }],
    });
    assert.equal(unknownModel.status, 404);
    assert.equal((unknownModel.body as Json).error.code, "model_not_found");
    assert.equal(chat([{ role: "user", content: "[[scenario:nope]]" }]).status, 400);
    assert.equal(handleRequest(fixtures, "POST", "/v1/chat/completions", "{").status, 400);
    assert.equal(chat([]).status, 400);
    assert.equal(handleRequest(fixtures, "GET", "/v1/unknown", "").status, 404);
  });
});

describe("embeddings and models", () => {
  test("one deterministic unit vector per input, in order", () => {
    const body: Json = post("/v1/embeddings", {
      model: "fake-embedding",
      input: ["alpha", "beta", "alpha"],
    }).body;
    assert.equal(body.data.length, 3);
    assert.deepEqual(
      body.data.map((entry: Json) => entry.index),
      [0, 1, 2],
    );
    assert.deepEqual(body.data[0].embedding, body.data[2].embedding);
    assert.notDeepEqual(body.data[0].embedding, body.data[1].embedding);
    assert.equal(body.data[0].embedding.length, 16);
    const length = Math.hypot(...body.data[0].embedding);
    assert.ok(Math.abs(length - 1) < 1e-5);
  });

  test("base64 encoding is little-endian float32", () => {
    const body: Json = post("/v1/embeddings", {
      model: "fake-embedding",
      input: "alpha",
      encoding_format: "base64",
    }).body;
    const buffer = Buffer.from(body.data[0].embedding, "base64");
    assert.equal(buffer.readFloatLE(0), embeddingOf("alpha", 16)[0]);
  });

  test("a chat model cannot embed and an embedding model cannot chat", () => {
    assert.equal(post("/v1/embeddings", { model: "fake-chat", input: "x" }).status, 400);
    assert.equal(
      post("/v1/chat/completions", { model: "fake-embedding", messages: [{ role: "user", content: "x" }] })
        .status,
      400,
    );
  });

  test("the model list names one model per task", () => {
    const body: Json = handleRequest(fixtures, "GET", "/v1/models", "").body;
    assert.ok(body.data.some((model: Json) => model.id === "fake-chat"));
    assert.ok(body.data.some((model: Json) => model.id === "fake-embedding"));
  });

  test("word splitting keeps the text intact", () => {
    assert.deepEqual(splitWords("a  b\nc"), ["a  ", "b\n", "c"]);
    assert.deepEqual(splitWords(""), []);
  });
});

describe("server", () => {
  test("refuses to start outside the e2e profile", async () => {
    const env = { ...process.env };
    delete env.JOFI_FAKE_AI;
    const child = spawn(process.execPath, [serverPath], { env, stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    child.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString();
    });
    const [code] = await once(child, "exit");
    assert.equal(code, 1);
    assert.match(stderr, /refusing to start/);
  });

  test("serves real HTTP with SSE streaming in the e2e profile", async () => {
    const directory = mkdtempSync(join(tmpdir(), "fake-ai-"));
    await mkdir(join(directory, "chat"));
    writeFileSync(
      join(directory, "chat", "default.json"),
      JSON.stringify({ description: "d", turns: [{ text: "a b c" }] }),
    );
    const child = spawn(process.execPath, [serverPath], {
      env: { ...process.env, JOFI_FAKE_AI: "e2e", JOFI_FAKE_AI_PORT: "0", JOFI_FAKE_AI_FIXTURES: directory },
      stdio: ["ignore", "pipe", "inherit"],
    });
    try {
      const [line] = (await once(child.stdout, "data")) as [Buffer];
      const port = /listening on (\d+)/.exec(line.toString())?.[1];
      assert.ok(port, line.toString());
      const response = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
        method: "POST",
        body: JSON.stringify({
          model: "fake-chat",
          stream: true,
          messages: [{ role: "user", content: "x" }],
        }),
      });
      assert.equal(response.headers.get("content-type"), "text/event-stream");
      const events = (await response.text()).split("\n\n").filter(Boolean);
      assert.equal(events.at(-1), "data: [DONE]");
      assert.equal(events.length, 6);
    } finally {
      child.kill("SIGTERM");
      await once(child, "exit");
    }
  });
});
