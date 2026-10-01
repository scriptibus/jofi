// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.scriptibus.jofi.architecture.JofiPackages.PORT
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool

/**
 * MCP tools are controllers for AI clients (AGENTS.md §3, ADR-0053): no business logic, one use case each, so
 * the built-in chat, external clients and the REST API always reach the same rules. Shared by the production
 * check and the known-bad fixtures ([McpToolRulesTest]).
 */
object McpToolRules {
    val toolsAreNamedToolInAnMcpAdapter: ArchRule =
        classes()
            .that()
            .implement(McpTool::class.java)
            .should()
            .haveSimpleNameEndingWith("Tool")
            .andShould()
            .resideInAPackage("..adapter.mcp..")
            .allowEmptyShould(true)

    /** The constructor takes exactly one use case, and the tool calls that use case and no other. */
    val toolsCallExactlyOneUseCase: ArchRule =
        classes()
            .that()
            .implement(McpTool::class.java)
            .should(receiveAndCallExactlyOneUseCase())
            .because("an MCP tool translates its arguments and calls exactly one use case")
            .allowEmptyShould(true)

    /**
     * Not only the tools: no class in an MCP adapter (helpers and result types included) reaches a port, an
     * adapter or a repository, so a tool cannot route around its one use case through a helper.
     */
    val toolsDoNotUsePortsAdaptersOrRepositories: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..adapter.mcp..")
            .or()
            .implement(McpTool::class.java)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(PORT)
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Adapter")
            .because("MCP tools contain no logic and only call one use case")
            .allowEmptyShould(true)

    private fun receiveAndCallExactlyOneUseCase(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("receive exactly one use case and call only that one") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val constructors = item.constructors.filterNot { JavaModifier.SYNTHETIC in it.modifiers }
                val received =
                    constructors
                        .singleOrNull()
                        ?.rawParameterTypes
                        ?.singleOrNull()
                        ?.takeIf(::isUseCase)
                val called =
                    item.methodCallsFromSelf
                        .map { it.targetOwner }
                        .filter(::isUseCase)
                        .map { it.name }
                        .toSet()
                val satisfied = received != null && called == setOf(received.name)
                val parameters = constructors.map { constructor -> constructor.rawParameterTypes.map { it.simpleName } }
                val message = "${item.name} receives $parameters and calls the use cases $called"
                events.add(SimpleConditionEvent(item, satisfied, message))
            }
        }

    private fun isUseCase(type: JavaClass): Boolean =
        type.simpleName.endsWith("UseCase") && type.packageName.endsWith(".application")
}
