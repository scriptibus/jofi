// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Entry point of the fake AI provider container (compose.e2e.yaml, service `fake-ai`). No dependencies
// beyond Node itself, so the container needs no install step and no internet.
//
// Run: JOFI_FAKE_AI=e2e node tests/stack/fake-ai/server.ts   (Node 24 strips the types natively)
// Env: JOFI_FAKE_AI (must be "e2e"), JOFI_FAKE_AI_PORT (default 8080), JOFI_FAKE_AI_FIXTURES (directory).

import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { handleRequest } from "./fake-ai.ts";
import { loadFixtures } from "./fixtures.ts";

const MAX_BODY_BYTES = 5 * 1024 * 1024;

// The fake answers every prompt without a key. It must never stand in for a real provider, so it only
// starts when the e2e compose overlay says so explicitly; production compose.yaml never sets this.
if (process.env.JOFI_FAKE_AI !== "e2e") {
  console.error("fake-ai: refusing to start: JOFI_FAKE_AI=e2e is only set by the e2e compose profile.");
  process.exit(1);
}

const fixtureDirectory =
  process.env.JOFI_FAKE_AI_FIXTURES ?? fileURLToPath(new URL("./fixtures", import.meta.url));
const fixtures = loadFixtures(fixtureDirectory);
const port = Number(process.env.JOFI_FAKE_AI_PORT ?? 8080);

const server = createServer((request, response) => {
  const chunks: Buffer[] = [];
  let size = 0;
  request.on("data", (chunk: Buffer) => {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      if (!response.headersSent) response.writeHead(413).end();
      request.destroy();
      return;
    }
    chunks.push(chunk);
  });
  request.on("end", () => {
    if (response.headersSent) return;
    const answer = handleRequest(
      fixtures,
      request.method ?? "GET",
      request.url ?? "/",
      Buffer.concat(chunks).toString("utf8"),
    );
    if (answer.summary !== "health") {
      console.log(`fake-ai: ${request.method} ${request.url} -> ${answer.status} (${answer.summary})`);
    }
    response.writeHead(answer.status, answer.headers);
    if (answer.events === undefined) {
      response.end(JSON.stringify(answer.body));
      return;
    }
    for (const event of answer.events) response.write(`data: ${event}\n\n`);
    response.end();
  });
});

server.listen(port, "0.0.0.0", () => {
  const address = server.address();
  const actualPort = typeof address === "object" && address !== null ? address.port : port;
  console.log(`fake-ai: listening on ${actualPort} with fixtures for ${[...fixtures.keys()].join(", ")}`);
});

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => process.exit(0));
}
