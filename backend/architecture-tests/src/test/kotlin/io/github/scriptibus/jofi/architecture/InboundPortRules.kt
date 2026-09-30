// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration

/**
 * Inbound ports (ADR-0041), checked on the sources with Konsist and shared by the production check and
 * the fixtures: every `<Verb><Noun>Port` in `..application.port.inbound..` has exactly one implementor,
 * the class `<Verb><Noun>UseCase` in `..application`. No adapter implements an inbound port, so REST
 * controllers and MCP tools always reach the same use case.
 */
object InboundPortRules {
    const val INBOUND_PORTS = "..application.port.inbound.."

    /**
     * Ports a contract declared whose use case lands in a later PR, with that issue. The feature PR
     * removes its entries (a listed port that has an implementor fails, as does one that no longer exists).
     */
    val AWAITING_USE_CASE: Map<String, String> =
        mapOf(
            "SearchApplicationsPort" to "#83",
            "LinkApplicationContactsPort" to "#90",
            "AddApplicationSourcePort" to "#96",
            "LogInterviewPort" to "#91",
            "UpdateInterviewPort" to "#91",
            "GetInterviewPort" to "#91",
            "ListInterviewsPort" to "#91",
            "DeleteInterviewPort" to "#91",
            "ListUpcomingInterviewsPort" to "#92",
        )

    /** Every broken rule in [scope], as readable messages; empty when all hold. */
    fun violations(
        scope: KoScope,
        awaiting: Map<String, String> = AWAITING_USE_CASE,
    ): List<String> {
        val ports = scope.interfaces().filter { it.resideInPackage(INBOUND_PORTS) }
        val classes = scope.classes()
        val stale = (awaiting.keys - ports.map { it.name }.toSet()).map { "$it is awaited but does not exist" }
        return stale + ports.flatMap { port -> violationsOf(port, classes.filter { implements(it, port) }, awaiting) }
    }

    private fun violationsOf(
        port: KoInterfaceDeclaration,
        implementors: List<KoClassDeclaration>,
        awaiting: Map<String, String>,
    ): List<String> {
        val useCase = port.name.removeSuffix("Port") + "UseCase"
        val names = implementors.mapNotNull { it.fullyQualifiedName }.sorted()
        val count =
            when {
                !port.name.endsWith("Port") -> {
                    "${port.name} is not named <Verb><Noun>Port"
                }

                port.name in awaiting && implementors.isNotEmpty() -> {
                    "${port.name} is implemented ($names): remove it from AWAITING_USE_CASE"
                }

                port.name !in awaiting && implementors.size != 1 -> {
                    "${port.name} needs exactly one implementor, $useCase, but has $names"
                }

                else -> {
                    null
                }
            }
        val strangers =
            implementors
                .filterNot { it.name == useCase && it.resideInPackage("..application") }
                .map { "${it.fullyQualifiedName} implements ${port.name}; only $useCase in ..application may" }
        return listOfNotNull(count) + strangers
    }

    private fun implements(
        type: KoClassDeclaration,
        port: KoInterfaceDeclaration,
    ): Boolean = type.parents().any { it.name == port.name }
}
