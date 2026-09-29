<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0003: JVM with AOT cache, no GraalVM native image for now

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §2.1

## Context

Native images start faster and use less memory, but take minutes to build, need lots of RAM, and reflection-heavy libraries (Spring AI, jOOQ, Tika) break in subtle ways.

## Decision

Run on the JVM (JDK 25) using the JDK's AOT cache for faster startup. A nightly native build may be added later to evaluate viability before any switch.

## Consequences

Faster agent feedback loop and CI. Higher memory use (~300–500 MB), acceptable for a single-user home server.
