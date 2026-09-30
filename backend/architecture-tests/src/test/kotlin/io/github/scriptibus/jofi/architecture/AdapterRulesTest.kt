// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.scriptibus.jofi.architecture.JofiPackages.ADAPTER
import io.github.scriptibus.jofi.architecture.JofiPackages.PORT
import org.junit.jupiter.api.Test

/** Adapter naming and "controllers only call use cases", checked on the compiled classes. */
class AdapterRulesTest {
    private val classes = JofiClasses.production

    @Test
    fun `controllers are named Controller and live in an adapter package`() {
        classes()
            .that()
            .areAnnotatedWith(REST_CONTROLLER)
            .should()
            .haveSimpleNameEndingWith("Controller")
            .andShould()
            .resideInAPackage(ADAPTER)
            .check(classes)
    }

    @Test
    fun `controllers do not use ports, adapters or repositories directly`() {
        noClasses()
            .that()
            .areAnnotatedWith(REST_CONTROLLER)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(PORT)
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Adapter")
            .because("controllers contain no logic and only call use cases")
            .check(classes)
    }

    @Test
    fun `port implementations are named Adapter or Repository`() {
        classes()
            .that()
            .resideInAPackage(ADAPTER)
            .and()
            .implement(PORT_INTERFACE)
            .should()
            .haveSimpleNameEndingWith("Adapter")
            .orShould()
            .haveSimpleNameEndingWith("Repository")
            .check(classes)
    }

    @Test
    fun `only setup adapter ai uses the provider-facing AI port`() {
        AdapterRules.onlyTheAiAdapterUsesAiProviderPort.check(classes)
    }

    @Test
    fun `only adapters net makes outbound HTTP calls`() {
        AdapterRules.onlyTheNetAdapterMakesOutboundHttpCalls.check(classes)
    }

    @Test
    fun `no AI SDK reads its settings from the environment`() {
        AdapterRules.noAiSdkReadsTheEnvironment.check(classes)
    }

    @Test
    fun `ports are interfaces`() {
        classes()
            .that()
            .resideInAPackage(PORT)
            .and()
            .areTopLevelClasses()
            .should()
            .beInterfaces()
            .check(classes)
    }

    private companion object {
        const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"

        val PORT_INTERFACE: DescribedPredicate<JavaClass> =
            DescribedPredicate.describe("a port") { it.packageName.contains(".application.port") }
    }
}
