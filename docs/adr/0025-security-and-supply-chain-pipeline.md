<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0025: Security and supply-chain pipeline

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.6

## Context

Jofi handles personal data and runs third-party code in CI.

## Decision

Secrets: gitleaks, GitHub secret scanning + push protection. Code: CodeQL, Semgrep. Supply chain: Gradle dependency verification + locking + wrapper validation, pnpm minimum release age and install-script allowlist, Renovate, OSV-Scanner, dependency-review. CI: actions pinned by SHA, least-privilege permissions, zizmor, OpenSSF Scorecard. Containers: Hadolint, Trivy, non-root, read-only where possible, `pdf` without network, images with SBOM and attestations. Threat model in `docs/threat-model.md`.

## Consequences

More CI time; findings land in GitHub code scanning in one place.
