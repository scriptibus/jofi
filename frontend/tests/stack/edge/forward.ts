// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The only way into the e2e stack (compose.e2e.yaml, service `edge`): a plain TCP forwarder from the
// published port on 127.0.0.1 to `app`. The app, worker, database, fake AI and WireMock sit on an
// internal network without a route to the internet; Docker cannot publish ports from such a network,
// so this container joins both networks and forwards bytes, nothing else (no headers are added).
//
// Env: JOFI_E2E_UPSTREAM_HOST (default app), JOFI_E2E_UPSTREAM_PORT (default 8080), JOFI_E2E_LISTEN_PORT
// (default 8080).

import { connect, createServer } from "node:net";

const upstreamHost = process.env.JOFI_E2E_UPSTREAM_HOST ?? "app";
const upstreamPort = Number(process.env.JOFI_E2E_UPSTREAM_PORT ?? 8080);
const listenPort = Number(process.env.JOFI_E2E_LISTEN_PORT ?? 8080);

const server = createServer((client) => {
  const upstream = connect(upstreamPort, upstreamHost);
  client.pipe(upstream).pipe(client);
  client.on("error", () => upstream.destroy());
  upstream.on("error", () => client.destroy());
});

server.listen(listenPort, "0.0.0.0", () => {
  console.log(`edge: forwarding :${listenPort} to ${upstreamHost}:${upstreamPort}`);
});

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => process.exit(0));
}
