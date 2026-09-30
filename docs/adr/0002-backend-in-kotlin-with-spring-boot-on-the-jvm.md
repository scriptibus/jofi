<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0002: Backend in Kotlin with Spring Boot on the JVM

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §2, §3; 02-decisions.md rounds 4–5

## Context

Lucas wants a strongly validatable, classic-OO backend (not JavaScript/TypeScript) with hexagonal architecture. Candidates were Kotlin + Spring Boot, C# + ASP.NET Core and Kotlin + Ktor.

## Decision

Kotlin + Spring Boot 4 on JDK 25 (LTS), Spring MVC on virtual threads, Gradle Kotlin DSL. Deciding factor: Spring AI covers all five AI providers plus MCP natively, and Tika/JGit/PDFBox cover the document-heavy parts. C# was a close second; Ktor was rejected because we would rebuild what Spring AI and Spring Modulith provide.

## Consequences

Null safety, sealed types and exhaustive `when` are compile-time checks. Build times are slower than .NET; mitigated with build/configuration cache and per-module tests. Blocking code on virtual threads, no reactive or coroutine complexity.
