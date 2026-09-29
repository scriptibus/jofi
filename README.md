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

## Documentation

- [Requirements](docs/spec/03-requirements-spec.md) · [Tech stack](docs/spec/04-tech-stack-proposal.md) ·
  [Design brief](docs/spec/06-design-brief.md)
- [Architecture decisions](docs/adr/README.md) · [Threat model](docs/threat-model.md)
- [Rules for contributors and coding agents](AGENTS.md)

## Contributing

Jofi is mostly built by coding agents working on one issue each, reviewed by CI, review lenses and a human.
Human contributions are welcome under the same rules: read [AGENTS.md](AGENTS.md) first.

## Licence

[AGPL-3.0-or-later](LICENSE). Copyright 2026 Jofi contributors.
