<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Jofi

**Jofi does the donkey work of a job search.** A self-hosted, AI-assisted job application manager: it finds
jobs, scores them against what you want and what you can offer, builds tailored application documents from
your own knowledge base, tracks every application to the offer, and trains you for interviews.

- **Local-first and private.** Runs on your laptop, NAS or home server with Docker Compose. Your data stays
  on your instance; AI calls go only to the provider you choose (Anthropic, OpenAI, Google, Mistral or any
  OpenAI-compatible endpoint such as Ollama).
- **Human in the loop.** Jofi proposes, you decide. It never sends anything on its own.
- **German and English** from day one.

> **Status:** early development (milestone M0, foundation). Not usable yet.

## Run it

You need Docker with Compose v2, or Podman with podman-compose.

```sh
cp .env.example .env    # set JOFI_DB_PASSWORD before the first start
docker compose up -d    # or: podman-compose up -d (the first run builds the image)
```

Then open <http://127.0.0.1:8080>. The stack has three containers: `app` (web UI and API), `worker`
(background jobs, same image) and `db` (PostgreSQL with pgvector). Data lives in the named volumes `jofi-db`
and `jofi-data`; `docker compose down` keeps them, `docker compose down --volumes` deletes them.

**Localhost only by default.** Jofi is published on `127.0.0.1` and can't be reached from other devices.
To expose it on your network, set `JOFI_BIND_ADDRESS=0.0.0.0` (or one interface's address) in `.env`, and
only once login is available. For phone access prefer [Tailscale](https://tailscale.com/) over opening ports.
The database is never published on the host.

The containers run as non-root users on read-only root filesystems. `scripts/compose-smoke-test.sh`
checks all of this and runs in CI.

## Documentation

- [Requirements](docs/spec/03-requirements-spec.md) · [Tech stack](docs/spec/04-tech-stack-proposal.md) ·
  [Design brief](docs/spec/06-design-brief.md)
- [Architecture decisions](docs/adr/README.md) · [Threat model](docs/threat-model.md)
- [Rules for contributors and coding agents](AGENTS.md)

## Development

The backend lives in `backend/` (Kotlin, Spring Boot, Gradle), the frontend in `frontend/` (React, TypeScript,
pnpm). `cd backend && ./gradlew check` and `cd frontend && pnpm check` run what CI runs; see the command table
in [AGENTS.md](AGENTS.md#9-commands). The backend build and tests start PostgreSQL through Testcontainers, so
they need Docker or Podman (with its Docker-compatible socket). `./gradlew :bootstrap:bootTestRun` runs the app
against a throwaway database; a real one is configured with `JOFI_DB_URL`, `JOFI_DB_USERNAME` and `JOFI_DB_PASSWORD`.

**End-to-end tests** run Playwright against the whole stack: `cd frontend && pnpm e2e` builds the image, starts
the `e2e` compose profile (`compose.yaml` + `compose.e2e.yaml`: the app on a network without internet, a
deterministic fake AI provider, WireMock and seeded demo data, published on <http://127.0.0.1:8180>), runs the
tests in light, dark and phone layouts with an accessibility check, and removes the stack again. No API keys
or internet needed. With Podman: `COMPOSE=podman-compose CONTAINER=podman pnpm e2e`. Details, the fake AI's
fixture format and the seed format are in [frontend/AGENTS.md](frontend/AGENTS.md#full-stack-e2e-adr-0036).

## Contributing

Jofi is mostly built by coding agents working on one issue each, reviewed by CI, review lenses and a human.
Human contributions are welcome under the same rules: read [AGENTS.md](AGENTS.md) first.

## Licence

[AGPL-3.0-or-later](LICENSE). Copyright 2026 Jofi contributors.
