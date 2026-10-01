// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.scriptibus.jofi.applications.adapter.mcp.GetApplicationTool
import io.github.scriptibus.jofi.applications.adapter.mcp.SearchApplicationsTool
import io.github.scriptibus.jofi.fixture.adapter.mcp.GoodFixtureTool
import io.github.scriptibus.jofi.fixture.adapter.mcp.IdleFixtureTool
import io.github.scriptibus.jofi.fixture.adapter.mcp.MisnamedFixtureHandler
import io.github.scriptibus.jofi.fixture.adapter.mcp.PortFixtureTool
import io.github.scriptibus.jofi.fixture.adapter.mcp.TwoUseCasesFixtureTool
import io.github.scriptibus.jofi.fixture.adapter.web.SharedMcpInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.application.GoodThingUseCase
import io.github.scriptibus.jofi.fixture.application.SharedThingUseCase
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The MCP tool rules on the production classes, and against known-good and known-bad fixtures. */
class McpToolRulesTest {
    private val production = JofiClasses.production

    @Test
    fun `MCP tools are named Tool, live in an MCP adapter and call exactly one use case`() {
        McpToolRules.toolsAreNamedToolInAnMcpAdapter.check(production)
        McpToolRules.toolsCallExactlyOneUseCase.check(production)
        McpToolRules.toolsDoNotUsePortsAdaptersOrRepositories.check(production)
    }

    @Test
    fun `the rules see the production tools`() {
        production
            .filter { it.isAssignableTo(McpTool::class.java) && !it.isInterface }
            .map { it.name } shouldContainAll
            listOf(SearchApplicationsTool::class.java.name, GetApplicationTool::class.java.name)
    }

    @Test
    fun `a tool with one called use case passes`() {
        val classes = fixtures(GoodFixtureTool::class.java)

        McpToolRules.toolsCallExactlyOneUseCase.evaluate(classes).hasViolation() shouldBe false
        McpToolRules.toolsDoNotUsePortsAdaptersOrRepositories.evaluate(classes).hasViolation() shouldBe false
        McpToolRules.toolsAreNamedToolInAnMcpAdapter.evaluate(classes).hasViolation() shouldBe false
    }

    @ParameterizedTest
    @ValueSource(classes = [TwoUseCasesFixtureTool::class, PortFixtureTool::class, IdleFixtureTool::class])
    fun `a tool with two use cases, a port or an unused use case is rejected`(tool: Class<*>) {
        McpToolRules.toolsCallExactlyOneUseCase.evaluate(fixtures(tool)).hasViolation() shouldBe true
    }

    @Test
    fun `a tool that uses a port is rejected by the dependency rule too`() {
        val classes = fixtures(PortFixtureTool::class.java)

        McpToolRules.toolsDoNotUsePortsAdaptersOrRepositories.evaluate(classes).hasViolation() shouldBe true
    }

    @Test
    fun `a tool not named Tool is rejected`() {
        val classes = fixtures(MisnamedFixtureHandler::class.java)

        McpToolRules.toolsAreNamedToolInAnMcpAdapter.evaluate(classes).hasViolation() shouldBe true
    }

    @Test
    fun `MCP adapters may use the shared MCP code, other adapter kinds may not`() {
        val tool = ClassFileImporter().importClasses(GoodFixtureTool::class.java, Untrusted::class.java)
        val web = ClassFileImporter().importClasses(SharedMcpInWebAdapterFixture::class.java, Untrusted::class.java)

        AdapterRules.adaptersAreIndependent.evaluate(tool).hasViolation() shouldBe false
        AdapterRules.adaptersAreIndependent.evaluate(web).hasViolation() shouldBe true
    }

    private fun fixtures(vararg tools: Class<*>) =
        ClassFileImporter().importClasses(
            *tools,
            McpTool::class.java,
            GoodThingUseCase::class.java,
            SharedThingUseCase::class.java,
        )
}
