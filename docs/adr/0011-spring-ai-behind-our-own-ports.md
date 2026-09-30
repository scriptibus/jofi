<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0011: Spring AI behind our own ports

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §3.3; refined by ADR-0032 (gateway and provider port)
  and ADR-0037 (provider clients and transport)

## Context

Jofi supports Anthropic, OpenAI, Google Gemini, Mistral and OpenAI-compatible endpoints, with per-task model choice, capability checks, cost tracking and a 'never send to AI' filter.

## Decision

Application-layer ports (`LlmPort`, `EmbeddingPort`, `SpeechToTextStreamPort`, `TextToSpeechStreamPort`) are implemented in `adapters/ai` with Spring AI. Routing, capability checks, cost tracking and the privacy filter live in the application layer. Spring AI types never leave `adapters/ai`. A deterministic fake provider implements the same ports for tests.

## Consequences

Provider changes are contained in one adapter. The provider-agnostic and privacy lenses watch the boundary.
