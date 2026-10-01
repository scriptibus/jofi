<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/mcp

The MCP server (ADR-0012, ADR-0053): Streamable HTTP at `/mcp` through Spring AI's WebMvc transport over the
official MCP Java SDK, with no Spring AI starter. Tool reference: `docs/mcp-tools.md`.

| Class | What |
|---|---|
| `shared.adapter.mcp.McpTool` | the tool contract: name, description, JSON Schema, read-only hint, `call(ToolCall)` |
| `shared.adapter.mcp.McpToolSpecifications` | runs every call: caller from the transport context, argument errors, serialisation, the "never send to AI" filter |
| `shared.adapter.mcp.JofiMcpServer` | transport + `McpSyncServer`, bounded sessions; bootstrap registers its routes |
| `shared.adapter.mcp.McpCallers` | the authenticated caller as actor (a session is `Actor.Ai`) |
| `shared.adapter.mcp.SameOriginValidator`, `McpOriginFilter` | 403 for a foreign `Origin`, in the security chain and in the transport |
| `shared.adapter.mcp.Untrusted` | the mark on third-party content in results |
| `shared.adapter.mcp.TwoStepDelete`, `HumanConfirmer` | drives a delete tool's two steps; the user (MCP elicitation), never the model, answers; the token stays server-side |
| `<context>.adapter.mcp.*Tool` | the tools, one use case each |

Adding a tool:
- A `@Component` class `<Verb><Noun>Tool` in `<context>.adapter.mcp`, implementing `McpTool`, whose
  constructor takes exactly one `*UseCase` and which calls only that (`McpToolRules`). Translate arguments
  with `ToolArguments` and the domain's `*Input.validate()`; map failures to `ToolAnswer.Error` with stable
  codes and no stored content.
- Never take the actor from arguments: use `call.caller`. Never name `Actor.User` (`SetupRules`). Deletes and
  outward actions go through the use case's confirmation gate (ADR-0039), never through the tool: a delete tool
  wraps its use case call in `TwoStepDelete.run`, which asks the user through elicitation. No elicitation means
  nothing runs (`confirmation-unavailable`); never put a token in a result.
- Wrap text from postings, pages or uploads in `Untrusted`. Return flaggable items (knowledge, M2) only
  after leaving out the flagged ones; the server's value filter is the second line.
- Write the result as DTOs in the same package; never return domain objects.
- Test the translation (real use case, mocked repository), and extend `McpContractTest` (bootstrap) to list
  and call the tool with the MCP SDK client. Add the tool to `docs/mcp-tools.md`.
