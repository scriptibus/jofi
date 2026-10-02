// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.system.application.RecoverRestoreUseCase
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy

/**
 * The two-step confirmation (ADR-0039) enforced on the compiled classes, shared by the production
 * check ([ConfirmationRulesTest]) and the known-bad fixtures, so both evaluate the same rule.
 */
object ConfirmationRules {
    private const val WEB = "org.springframework.web.bind.annotation."
    private const val REST_CONTROLLER = "${WEB}RestController"
    private const val REQUEST_MAPPING = "${WEB}RequestMapping"
    private const val REQUEST_HEADER = "${WEB}RequestHeader"
    private val DESTRUCTIVE_PORT_METHOD = Regex("^(delete|remove|send|purge).*")

    /**
     * `MasterKeyBackupPort.reinstate` replaces the master keyset without a confirmation proof: it
     * exists only to undo an interrupted restore (ADR-0042). Nothing but `RecoverRestoreUseCase` (and
     * the adapter implementing the port) may call it.
     */
    val onlyRestoreRecoveryReinstatesTheKeyset: ArchRule =
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(RecoverRestoreUseCase::class.java.name)
            .and()
            .doNotImplement(MasterKeyBackupPort::class.java)
            .should()
            .callMethod(MasterKeyBackupPort::class.java, "reinstate", MasterKeysetCopy::class.java)
            .because("only restore recovery may put a keyset back without a confirmation (ADR-0042)")
            .allowEmptyShould(true)

    /**
     * Outward-facing endpoints that are not `DELETE` (e.g. a future "send email"), as `"POST /api/..."`.
     * They need the confirmation header too. Add every such endpoint here when it is created, and
     * destructive ones that are not `DELETE` as well.
     */
    val OUTWARD_FACING_ENDPOINTS: Set<String> =
        setOf(
            // Replaces all data with a backup (ADR-0042).
            "POST /api/system/backup/restores/{id}",
        )

    /**
     * `DELETE` handlers that are not destructive for the user's data and need no confirmation,
     * as `"<controller simple name>.<method>"` with the reason. Logging out, for instance, would go
     * here if it were a `DELETE`. Every entry needs a human review.
     */
    val ENDPOINTS_WITHOUT_CONFIRMATION: Map<String, String> =
        mapOf(
            "ModelPriceController.clearModelPrice" to
                "meets all of: a setting the user can enter again, changeable by the logged-in user only " +
                "(SetupRules; the AI, scanners and external clients are refused), its old value is kept in the " +
                "changelog, nothing cascades, and recorded cost entries keep their cost (#142, ADR-0055). " +
                "Later entries must meet the same criteria",
        )

    /**
     * Use cases that call a destructive port method without the gate, with the reason. Every entry
     * needs a human review.
     */
    val USE_CASES_WITHOUT_CONFIRMATION: Map<String, String> =
        mapOf(
            "ResetPasswordUseCase" to
                "JOFI_RESET_PASSWORD is set by whoever runs the server: the operator's own action at startup, " +
                "not a request",
            "CleanUpExpiredSessionsUseCase" to
                "housekeeping job (ADR-0038): drops sessions past their lifetime, no user data and no request",
        )

    /** Every `DELETE` (and [outward]-facing) handler takes the `Jofi-Confirmation` header. */
    fun destructiveEndpointsTakeTheConfirmationHeader(outward: Set<String> = OUTWARD_FACING_ENDPOINTS): ArchRule =
        classes()
            .that()
            .areAnnotatedWith(REST_CONTROLLER)
            .should(takeTheConfirmationHeaderOnDestructiveMappings(outward))
            .because("deletes and outward-facing actions need the server-enforced confirmation (ADR-0039)")

    /**
     * A use case that calls a port method named `delete*`/`remove*`/`send*`/`purge*` goes through
     * [ConfirmActionUseCase], unless the port method itself demands a `ConfirmationResult.Confirmed`.
     */
    val destructivePortCallsPassTheGate: ArchRule =
        classes()
            .that()
            .resideInAPackage("..application")
            .should(passTheGateBeforeDestructivePortCalls())
            .because("a delete or outward action runs only after the user confirmed it (ADR-0039)")

    /** Only the gate creates and checks pending confirmations, so only it can mint a `Confirmed`. */
    val onlyTheGateMintsConfirmations: ArchRule =
        noClasses()
            .that()
            .doNotBelongToAnyOf(ConfirmActionUseCase::class.java, PendingConfirmation::class.java)
            .should()
            .callConstructorWhere(constructs(PendingConfirmation::class.java, ConfirmationResult.Confirmed::class.java))
            .orShould()
            .callMethodWhere(isPendingCheck())
            .because("only ConfirmActionUseCase may confirm an action (ADR-0039)")

    private fun constructs(vararg types: Class<*>): DescribedPredicate<JavaConstructorCall> {
        val description = "a constructor of ${types.joinToString { it.simpleName }}"
        return DescribedPredicate.describe(description) { call -> types.any { call.target.owner.isEquivalentTo(it) } }
    }

    private fun isPendingCheck() =
        DescribedPredicate.describe<JavaMethodCall>("PendingConfirmation.check") {
            it.target.owner.isEquivalentTo(PendingConfirmation::class.java) && it.target.name == "check"
        }

    private fun takeTheConfirmationHeaderOnDestructiveMappings(outward: Set<String>) =
        object : ArchCondition<JavaClass>("take the ${Confirmations.HEADER} header on destructive mappings") {
            override fun check(
                controller: JavaClass,
                events: ConditionEvents,
            ) {
                controller.methods
                    .filter { isDestructive(controller, it, outward) }
                    .filterNot { "${controller.simpleName}.${it.name}" in ENDPOINTS_WITHOUT_CONFIRMATION }
                    .filterNot(::takesTheHeader)
                    .forEach { events.add(SimpleConditionEvent.violated(it, "${it.fullName} lacks the header")) }
            }
        }

    private fun isDestructive(
        controller: JavaClass,
        method: JavaMethod,
        outward: Set<String>,
    ): Boolean {
        val mappings = mappingsOf(controller, method)
        return mappings.any { it.startsWith("DELETE ") } || mappings.any { it in outward }
    }

    /** `"<METHOD> <path>"` for every verb and path a handler is mapped to. */
    private fun mappingsOf(
        controller: JavaClass,
        method: JavaMethod,
    ): List<String> {
        val prefixes = controller.annotationOrNull(REQUEST_MAPPING)?.let(::paths)?.ifEmpty { null } ?: listOf("")
        return method.annotations.flatMap { annotation ->
            val verbs = verbsOf(annotation)
            val paths = paths(annotation).ifEmpty { listOf("") }
            verbs.flatMap { verb -> prefixes.flatMap { prefix -> paths.map { "$verb $prefix$it" } } }
        }
    }

    private fun verbsOf(annotation: JavaAnnotation<*>): List<String> {
        val name = annotation.rawType.name
        return when {
            name == REQUEST_MAPPING -> {
                (annotation.get("method").orElse(null) as? Array<*>).orEmpty().map { (it as JavaEnumConstant).name() }
            }

            name.startsWith(WEB) && name.endsWith("Mapping") -> {
                listOf(name.removePrefix(WEB).removeSuffix("Mapping").uppercase())
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun paths(annotation: JavaAnnotation<*>): List<String> =
        listOf("value", "path").flatMap { (annotation.get(it).orElse(null) as? Array<*>).orEmpty().map(Any?::toString) }

    private fun takesTheHeader(method: JavaMethod): Boolean =
        method.parameters.any { parameter ->
            parameter.annotations.any { annotation ->
                annotation.rawType.name == REQUEST_HEADER &&
                    listOf("value", "name").any { annotation.get(it).orElse(null) == Confirmations.HEADER }
            }
        }

    private fun passTheGateBeforeDestructivePortCalls() =
        object : ArchCondition<JavaClass>("pass the confirmation gate before destructive port calls") {
            override fun check(
                useCase: JavaClass,
                events: ConditionEvents,
            ) {
                if (useCase.simpleName in USE_CASES_WITHOUT_CONFIRMATION || usesTheGate(useCase)) return
                useCase.methodCallsFromSelf
                    .filter(::isUnguardedDestructivePortCall)
                    .forEach { call ->
                        val message =
                            "${useCase.name} calls ${call.target.fullName} without ConfirmActionUseCase or a Confirmed proof"
                        events.add(SimpleConditionEvent.violated(call, message))
                    }
            }
        }

    private fun usesTheGate(useCase: JavaClass): Boolean =
        useCase.constructors.any { constructor ->
            constructor.rawParameterTypes.any { it.isEquivalentTo(ConfirmActionUseCase::class.java) }
        }

    private fun isUnguardedDestructivePortCall(call: JavaMethodCall): Boolean {
        val target = call.target
        val isPort = target.owner.isInterface && target.owner.packageName.contains(".application.port")
        val takesProof = target.rawParameterTypes.any { it.isEquivalentTo(ConfirmationResult.Confirmed::class.java) }
        return isPort && DESTRUCTIVE_PORT_METHOD.matches(target.name) && !takesProof
    }

    private fun JavaClass.annotationOrNull(type: String): JavaAnnotation<JavaClass>? =
        if (isAnnotatedWith(type)) getAnnotationOfType(type) else null
}
