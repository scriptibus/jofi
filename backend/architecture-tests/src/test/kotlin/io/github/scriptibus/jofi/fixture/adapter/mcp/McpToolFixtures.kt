// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.mcp

import io.github.scriptibus.jofi.fixture.application.GoodThingUseCase
import io.github.scriptibus.jofi.fixture.application.SharedThingUseCase
import io.github.scriptibus.jofi.fixture.application.port.FixtureThingsPort
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall

// MCP tool fixtures for McpToolRulesTest. Test fixtures only, never production.

/** Known-good: one use case, called. */
class GoodFixtureTool(
    private val thing: GoodThingUseCase,
) : FixtureToolBase() {
    override fun call(call: ToolCall): ToolAnswer = ToolAnswer.Result(thing.execute())
}

/** Known-bad: two use cases, i.e. logic that combines them. */
class TwoUseCasesFixtureTool(
    private val thing: GoodThingUseCase,
    private val other: SharedThingUseCase,
) : FixtureToolBase() {
    override fun call(call: ToolCall): ToolAnswer = ToolAnswer.Result(thing.execute() + other.execute())
}

/** Known-bad: a port instead of a use case. */
class PortFixtureTool(
    private val things: FixtureThingsPort,
) : FixtureToolBase() {
    override fun call(call: ToolCall): ToolAnswer = ToolAnswer.Result(things.delete("id"))
}

/** Known-bad: receives one use case but never calls it. */
class IdleFixtureTool(
    @Suppress("unused") private val thing: GoodThingUseCase,
) : FixtureToolBase() {
    override fun call(call: ToolCall): ToolAnswer = ToolAnswer.Error("idle", "Does nothing.")
}

/** Known-bad: a tool that is not named `*Tool`. */
class MisnamedFixtureHandler(
    private val thing: GoodThingUseCase,
) : FixtureToolBase() {
    override fun call(call: ToolCall): ToolAnswer = ToolAnswer.Result(thing.execute())
}

abstract class FixtureToolBase : McpTool {
    override val name = "fixture"
    override val description = "A fixture."
    override val inputSchema = """{"type":"object"}"""
    override val readOnly = true
}
