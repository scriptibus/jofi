<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0015: PDF generation with HTML/CSS templates and Gotenberg

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §3.3

## Context

Generated CVs and cover letters must be ATS-friendly, and users can bring their own design.

## Decision

Templates are HTML/CSS rendered to PDF by Gotenberg (Chromium) in its own container without network access. PDFBox checks and post-processes (merging the Bewerbungsmappe, text-layer checks).

## Consequences

LLMs are fluent in HTML/CSS, which helps 'reproduce my design'. One more container; it has no egress.
