<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0053: The MCP server: Streamable HTTP, session auth, one use case per tool, filtered results

- Status: accepted
- Date: 2026-10-01
- Source: issue #116 (M1-12a); spec §9, §9.1, 04-tech-stack-proposal §3, §4.8b; threat model T2, T3; refines
  ADR-0012, ADR-0035, ADR-0043

## Context

ADR-0012 decided on one Spring AI MCP server for the built-in chat and external clients. The first tools
(search and get applications) need the details: which transport and which Spring AI artifacts, how a
request is authenticated and who the actor is, how the MCP transport's Origin/DNS-rebinding rule fits
the app's CSRF rules, where tools live given that adapters may not depend on each other, and how the
"never send to AI" rule and the untrusted-content rule reach every tool result.

## Decision

### Transport and libraries, without the starter

- **Streamable HTTP at `/mcp`**, stateful (MCP sessions with `Mcp-Session-Id`), so the server can later ask
  the user through MCP elicitation (ADR-0039, #117). The transport is Spring AI's
  `WebMvcStreamableServerTransportProvider` (`org.springframework.ai:mcp-spring-webmvc` 2.0.1, matching the
  Spring AI version of ADR-0040) over the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp` 2.0.1:
  `mcp-core` plus the Jackson 3 mapper; Spring AI 2.0.1 is built on SDK 2.0.0, 2.0.1 is its patch release
  with bounded HTTP reads).
- **No `spring-ai-starter-mcp-server-webmvc`**: like ADR-0040, nothing is auto-configured. The starter would
  pull in `spring-ai-model`, the annotation scanner and its own server bean; we build the transport and the
  `McpSyncServer` ourselves (`JofiMcpServer`, wired in `shared.config.McpConfiguration`) and register the
  transport's router function. Sessions are bounded (100, 30 minutes idle), since each holds memory.
- Docs consulted: Spring AI 2.0 reference, MCP server boot starter and the WebMvc transport API
  (https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html,
  https://docs.spring.io/spring-ai/docs/2.0.0/api/), MCP Java SDK server and client docs
  (https://java.sdk.modelcontextprotocol.io/), MCP specification 2025-11-25, transports, security warning
  (https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).

### Authentication, CSRF and Origin

- `/mcp` sits behind the **same security filter chain as `/api/**`**: a session is required (401 problem
  detail without one), and every unsafe request (POST, DELETE) needs the CSRF header (`csrf.spa()`,
  ADR-0035). An MCP client that uses the session therefore behaves like the SPA: it sends the session cookie
  and echoes `XSRF-TOKEN` in `X-XSRF-TOKEN`. External clients get their own revocable bearer tokens with
  #125; bearer requests carry no cookie, so they will not need the CSRF header.
- **Origin check** (MCP Streamable HTTP security rule): `SameOriginValidator` refuses with 403 every request
  whose `Origin` header is not Jofi's own origin (the host and port of the request's `Host`). It runs first
  in the security filter chain (`McpOriginFilter`, before CSRF and the session check, so the answer is 403
  as the specification requires, not 401) and again inside the transport. Jofi has no
  configured public host name (localhost by default, any proxy name when exposed), so the rule is
  same-origin, not an allowlist. Clients outside a browser send no `Origin` and pass. DNS rebinding cannot
  use `/mcp`: a rebound page runs under the attacker's host name, where the browser holds no Jofi session
  cookie, and Spring Security refuses the request before the transport sees it.
- **The actor comes from the authenticated request, never from tool arguments.** The transport's context
  extractor reads the principal on the request thread and puts the actor into the MCP transport context;
  `McpToolSpecifications` hands it to the tool as `ToolCall.caller`, and a call without one runs nothing. A
  session caller is an AI working for the logged-in user (the built-in chat, #121), so its changes will be
  recorded as `Actor.Ai` (spec §9); external clients become `Actor.ExternalClient(name)` with #125. MCP
  tools never name `Actor.User` (`SetupRules`).
- An MCP session is not bound to the login session that opened it: with one user and random session ids
  that is safe today. #125 binds sessions to the client token that created them.

### Tools: controllers for AI clients

- A tool implements `McpTool` (name, description, JSON Schema, read-only hint, `call`) and lives in
  `<context>.adapter.mcp` of the module `adapters/mcp`, as a Spring bean. Like a controller it translates
  its arguments (through the domain's `*Input.validate()` where one exists), calls **exactly one use case**
  and maps the result to a result DTO. `McpToolRules` (ArchUnit, with known-good and known-bad fixtures)
  enforces: implementors are named `*Tool` and live in an MCP adapter package; the constructor takes exactly
  one `*UseCase` and the tool calls that one and no other; no ports, adapters or repositories.
- The shared tool contract and the server live in the open shared kernel, `shared.adapter.mcp`. MCP
  adapters of every context may use it, a third narrow exemption from "adapters are independent" next to
  shared persistence and shared web (`AdapterRules.SHARED_MCP`, with fixtures).
- Domain failures become **tool results with `isError: true`** and a JSON body `{code, message, problems}`
  (stable codes like `not-found`, `invalid-arguments`, `unavailable`), not protocol errors, so a model can
  react. Arguments of the wrong shape are named, never echoed. An unexpected exception becomes
  `internal-error`; only its class name is logged. The SDK additionally validates every call against the
  tool's schema.

### Every result is filtered and posting content is marked

- `McpToolSpecifications` serialises every answer (results and errors) with the app's Jackson mapper and
  runs `FilterToolResultUseCase` on the JSON before it leaves: each flagged value of `AiVisibilityPort` is
  redacted inside JSON string values (`NeverSendFilter.applyToToolResult`, the ADR-0043 matching), so the
  JSON stays valid. If the flags cannot be read, or serialising, reading the flags or redacting throws,
  the call answers `privacy-filter-failed` and nothing of the result (fail closed); an exception must
  never reach the SDK, which would send its message and causes to the client unfiltered. Tools cannot skip the filter because they never write the response.
- Tools that will return flaggable items (knowledge, M2) must leave flagged items out before serialising;
  the value scan here is the second line, as in the gateway.
- Third-party text (the posting's title, location and source URLs today) is wrapped as `Untrusted`:
  `{"trust":"untrusted","notice":"…","content":…}`. The mark travels inside the result to every client, and
  the server's `instructions` tell clients what it means (threat model T2). The user's own fields (notes,
  decline reason) stay plain.
- The SDK's own logging is off (`io.modelcontextprotocol`), like Spring AI's: it logs messages at DEBUG and
  parser errors that can quote arguments.

## Consequences

- #117, #118 and #119 add tools by adding `*Tool` beans; the server, filter, actor and Origin handling come
  for free, and `McpContractTest` (MCP SDK client against the running app, part of `./gradlew check`)
  grows with them. Tool reference: `docs/mcp-tools.md`.
- The built-in chat (#121) is an MCP client of `/mcp`; it must carry the user's session and CSRF token, or
  use an in-process client that goes through the same `McpToolSpecifications`.
- The `.review/protected-paths.json` patterns and CODEOWNERS do not list `backend/adapters/mcp` yet; the
  human-in-the-loop lens should trigger on it (spec §4.8b). That is a reviewer change for Lucas, not part
  of this feature PR.
- A future public host name setting could turn the Origin rule into an allowlist and add a Host check.

## Amendment (#119): fields writable through tools are `Untrusted`

Added with the company and contact tools; nothing above is withdrawn except the last sentence of the
`Untrusted` bullet ("The user's own fields … stay plain") for fields a tool can write.

- Once a tool lets the model write a field without confirmation, that field can hold text from a prompt-injected
  session (threat model T2), and every later session or client would read it as the user's own words. So every
  field an MCP tool can write is returned wrapped as `Untrusted`: the company name, website, industry, size,
  locations, careers page and research notes, and a contact's name, role, channels and relationship notes.
- Fields no tool can write stay plain (the company preference and its reason, ids, versions, timestamps). A tool
  that starts writing such a field wraps it in the same change.
- This is the conservative choice. A second trust mark ("may be AI-written") or provenance taken from the
  changelog actor would let clients tell the user's own text from the model's; that is open for the maintainer.
