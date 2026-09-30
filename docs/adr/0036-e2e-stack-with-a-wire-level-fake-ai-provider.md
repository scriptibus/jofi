<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0036: e2e stack with a wire-level fake AI provider

- Status: accepted
- Date: 2026-09-30
- Source: issue #21 (M0-7), ADR-0027, ADR-0029, ADR-0032, ADR-0034; docs/spec/04-tech-stack-proposal.md §4.5, §4.8a

## Context

ADR-0027 asks for Playwright e2e tests against an `e2e` compose profile with a deterministic fake AI
provider, WireMock and seeded demo data, without real keys or internet. The fake could sit at three
levels: behind `LlmPort`/`EmbeddingPort` (then the AI gateway of #20, the "never send to AI" filter and
the cost meter would not run), behind `AiProviderPort` as a Spring bean (the gateway runs, but the Spring
AI adapter of #19, the SSRF guard and the HTTP mapping of errors do not, and fake code ships in the
production jar behind a switch), or on the wire, as an HTTP server the real adapter talks to.

Two more constraints: nothing e2e-only may be reachable in the production image or `compose.yaml`, and
the e2e user must exist once login lands (#16) without a back door in the app.

## Decision

- **The fake is an OpenAI-compatible HTTP server** in its own container (`frontend/tests/stack/fake-ai`,
  plain Node 24 with no dependencies, same pinned image as the Dockerfile's frontend stage). It serves
  `/v1/chat/completions` (tool calls, SSE streaming with the usage chunk), `/v1/embeddings` (float or
  base64) and `/v1/models`. Jofi reaches it as an ordinary `OPENAI_COMPATIBLE` provider with base URL
  `http://fake-ai:8080/v1`, so the gateway, the privacy filter, the meter, the Spring AI adapter, the SSRF
  guard and its provider allowlist all run in e2e exactly as in production. No new `ProviderKind`, no
  migration, no fake code in the backend.
- **Fixtures** are JSON files `fixtures/<task>/<scenario>.json`. The model name selects the task
  (`fake-<task>`; the seed assigns `fake-chat` to `CHAT` and so on), a `[[scenario:<name>]]` marker in any
  message selects the scenario (default `default`), and the number of earlier assistant messages selects
  the turn, which makes tool-call round trips deterministic. Error scenarios answer with the provider's
  HTTP status and error body (`Retry-After` included). Responses have no randomness, clock or delays;
  invalid fixtures stop the fake at startup. It refuses to start unless `JOFI_FAKE_AI=e2e`, which only
  the e2e overlay sets.
- **The stack** is `compose.yaml` plus the overlay `compose.e2e.yaml`, whose extra services carry the
  `e2e` profile (`scripts/e2e-stack.sh`, `pnpm e2e`, always with `--env-file /dev/null` so a developer's
  `.env` never leaks in). The production `app`, `worker` and `db` definitions are reused unchanged
  except for their network and image tag: `e2e-internal` (`internal: true`, no route out), shared with
  `fake-ai` and WireMock 3.13.2 (placeholder stubs for the M4 scanner sources), and `localhost/jofi:e2e`
  (same Dockerfile, so an e2e build never replaces a deployment's `localhost/jofi:local`). Compose
  cannot publish a port from an internal network, so `edge`, a 30-line TCP forwarder on both networks,
  publishes `127.0.0.1:8180`. Tests run on the host against it.
- **Seeding** has two formats, both idempotent. API steps (`tests/stack/seed/api.ts`, run by the
  Playwright setup project `seed`, which every browser project depends on) use only the public API:
  first run and login (#16) with a fixed e2e password, saving the session as Playwright storage state.
  First run always needs the one-time setup token (#16); the script reads it from
  `/data/secrets/setup-token` in the app container, as a user would, and the step sends it as
  `setupToken` (it fails loudly only when first run is due and no token was found). Before #16 the step
  detects that `/api/auth/session` is absent and does nothing. SQL steps (`tests/stack/seed/db/*.sql`,
  applied in one transaction each by the one-shot `seed` service) cover data that has no API yet, here
  the fake provider, its model assignments and capabilities, with one `SYSTEM`/`e2e-seed` changelog
  entry. SQL steps move to the API as endpoints appear (#23 for providers).
- **Isolation is tested**, not assumed. `scripts/e2e-stack.sh` checks on every run that the seed is
  complete and idempotent, and that `app`, `worker` and `fake-ai` each reach an internal service (a
  positive control on the same probe) but get "network unreachable" for a public address.
  `scripts/e2e-isolation-test.sh` checks that `compose.yaml` defines only `app`, `worker` and `db`,
  declares no profiles and mentions no e2e marker; that every marker occurs in the e2e sources (so the
  check cannot pass vacuously); and that no file name or decompressed entry of any jar in the image
  (`app.jar` and `lib/`) contains one. `.dockerignore` keeps `frontend/tests/` and
  `frontend/playwright/` out of the build context altogether.
- **CI**: the `e2e` job builds the image, runs `pnpm e2e` (light, dark, phone, axe) and the isolation
  test, and uploads the HTML report, traces, screenshots and container logs on failure. The frontend job
  no longer runs Playwright against `vite preview`; `pnpm e2e:preview` still does locally.

Images: `docker.io/wiremock/wiremock:3.13.2` (latest stable on Docker Hub, 2026-09-30; 4.0 is still beta),
the Dockerfile's `node:24.21.0-trixie-slim` and `pgvector/pgvector:0.8.6-pg18-trixie`, all pinned by digest.

Docs consulted: Compose file reference (profiles, merge and `!reset`,
https://docs.docker.com/reference/compose-file/profiles/, https://docs.docker.com/reference/compose-file/merge/),
Docker bridge and port publishing (https://docs.docker.com/engine/network/port-publishing/),
the OpenAI chat completion, stream chunk, embedding and model-list responses recorded for #19 (its
`adapters/ai` test fixtures; platform.openai.com refused automated reads), Playwright setup projects and
authentication (https://playwright.dev/docs/test-global-setup-teardown, https://playwright.dev/docs/auth,
which recommends a setup project over `globalSetup`), WireMock Docker image (https://wiremock.org/docs/standalone/docker/), Node.js type stripping
(https://nodejs.org/docs/latest-v24.x/api/typescript.html), and the OpenAI response fixtures recorded for #19.

## Consequences

- Features that call AI are tested end to end through the real provider path; scenario files replace
  mocks. Later milestones add fixtures, WireMock mappings and seed steps (format in `frontend/AGENTS.md`).
- The fake sees no `AiTask`, only the model name: the seed's `fake-<task>` naming is the contract.
- The e2e job builds the full image (several minutes); sharing the build with the `compose` job is a
  possible follow-up. The nightly full e2e is #28.
- `edge` itself has outbound access (it must publish a port); it runs only the forwarder.
- Before #16 the stack runs without login; after it, the seed step logs in with no change here.
- The app sees every request from the one `edge` address, never loopback. #16's per-client login backoff
  therefore treats all tests as one client: a test that enters wrong passwords must run on its own
  stack (or reset the throttle), or it slows down every later login in the run.
