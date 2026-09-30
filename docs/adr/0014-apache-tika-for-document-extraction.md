<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0014: Apache Tika for document extraction

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3

## Context

Users upload CVs, Arbeitszeugnisse and certificates (PDF, DOCX, scans).

## Decision

Apache Tika extracts text; Tesseract OCR is an optional profile; a vision-capable model is the fallback for hard cases (subject to the privacy filter).

## Consequences

Extraction works offline for most documents.
