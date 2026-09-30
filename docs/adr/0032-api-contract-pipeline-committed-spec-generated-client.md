<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0032: API contract pipeline: committed spec, build-time client, oasdiff with a human override

- Status: accepted
- Date: 2026-09-30
- Source: issue #12; refines ADR-0016

## Context

ADR-0016 chose springdoc-openapi, orval and oasdiff but left open where the spec lives, how it is
produced, whether generated code is committed, which error schema the API uses and how a breaking
change can be approved. The spec has to be readable by the backend, the frontend, the image build
and a CI job that compares it with the base branch.

## Decision

- **One committed contract:** `api/openapi.json` (OpenAPI 3.0, keys sorted, pretty-printed). It is
  the file oasdiff diffs and the input for the frontend client, so it is committed; it is never
  edited by hand.
- **Rendered by a test, not by the running app:** `OpenApiSpecTest` in `adapters/web` starts the
  controllers and controller advice of every context (use cases stubbed) with springdoc-openapi
  3.1.1 on the **test** classpath, renders `/v3/api-docs` and fails `./gradlew check` when the
  committed file differs; `./gradlew :adapters:web:updateOpenApiSpec` rewrites it. The app does not
  serve the spec or a UI, so springdoc (and swagger-core's Jackson 2) never reach the runtime
  classpath, and the check needs no database.
  Docs: https://springdoc.org/core-properties.html
- **Generated client at build time:** orval 8.36.0 writes TanStack Query hooks (fetch client
  through our `apiFetch` mutator) and Zod schemas to `frontend/src/api/generated/` (git-ignored),
  run by `pnpm typecheck`, `pnpm test` and `pnpm build`. Like the jOOQ code (ADR-0030) it cannot go
  stale; a contract change the frontend does not follow is a `tsc` error in `pnpm check`.
  Docs: https://orval.dev/docs/reference/configuration/output
- **One error schema:** every error is RFC 9457 problem details (`spring.mvc.problemdetails.enabled`
  for framework errors, plus a lowest-precedence catch-all advice that answers 500 without internal
  details, so nothing falls through to Spring Boot's differently shaped `/error` JSON).
  The contract declares a `ProblemDetail` schema and a `default` `application/problem+json`
  response on every operation; web adapters map sealed failure results to `ProblemDetail`.
  The client throws `ApiProblemError` carrying it.
- **Breaking changes:** the `api-breaking-changes` CI job runs oasdiff 1.32.1 (release binary,
  SHA-256 pinned) against the base branch's spec and fails on `ERR`. The only override is a line
  in `.github/oasdiff/breaking-changes-allowed.txt` (`--err-ignore`), a protected path that Lucas
  must approve. The GitHub Action is not used because it uploads specs to oasdiff.com by default.
  Docs: https://github.com/oasdiff/oasdiff/blob/main/docs/BREAKING-CHANGES.md

## Consequences

- Every controller change needs `updateOpenApiSpec` and a commit of `api/openapi.json`; CI says so.
- Operation ids come from controller method names and name the generated hooks
  (`getSystemInfo` -> `useGetSystemInfo`), so they must be unique and descriptive.
- Annotations such as `@Operation` or `@Schema` need `swagger-annotations` on the main classpath;
  add it when the first one is needed.
- The image build copies `api/openapi.json` into the frontend stage.
- `argparse` (Python-2.0, via orval) is a reviewed build-time licence exception.
