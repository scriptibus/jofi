// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import io.github.scriptibus.jofi.JofiApplication
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ChangeApplicationStatusPort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.EmbeddingPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.LlmPort
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.system.domain.SystemInfo
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModule
import org.springframework.modulith.core.ApplicationModules

/**
 * Spring Modulith view: each bounded context (direct sub-package of the base package) is an
 * application module. Modulith works on packages, so a context spanning several Gradle modules
 * (domain, application, adapters, bootstrap) is still one application module here.
 */
class ModulithTest {
    private val modules = ApplicationModules.of(JofiApplication::class.java)

    @Test
    fun `bounded contexts are detected as application modules`() {
        modules.map { it.identifier.toString() } shouldContainAll
            listOf("shared", "setup", "system", "companies", "applications", "tasks")
    }

    @Test
    fun `application modules respect their boundaries`() {
        modules.verify()
    }

    @Test
    fun `only the shared kernel is an open module`() {
        modules.filter { it.isOpen }.map { it.identifier.toString() } shouldContainExactly listOf("shared")
    }

    @Test
    fun `the shared kernel exposes its domain types and ports to every context`() {
        val exposed =
            listOf(
                Actor::class,
                Actor.Scanner::class,
                ChangelogEntry::class,
                ChangelogPort::class,
                AiTask::class,
                LlmPort::class,
                EmbeddingPort::class,
                OutboundHttpPort::class,
                JobSchedulerPort::class,
                SecretStorePort::class,
            )

        exposed.forEach { type -> module("shared").isExposed(type.java) shouldBe true }
    }

    @Test
    fun `a second context depends on the shared kernel and still verifies`() {
        // setup.domain.ModelAssignment uses shared.domain.ai.AiTask: before `shared` was open,
        // this dependency on an internal package failed verify().
        module("setup").contains(ModelAssignment::class.java) shouldBe true
        module("setup").getDirectDependencies(modules).containsModuleNamed("shared") shouldBe true
        module("setup").detectDependencies(modules).hasViolations() shouldBe false
    }

    @Test
    fun `other contexts keep their sub-packages internal`() {
        module("system").isExposed(SystemInfo::class.java) shouldBe false
        module("setup").isExposed(ModelAssignment::class.java) shouldBe false
    }

    @Test
    fun `companies exposes only its SPI to the applications context, which depends on it (ADR-0041)`() {
        val companies = module("companies")

        companies.namedInterfaces.getByName("spi").isPresent shouldBe true
        companies.isExposed(ApplicationCountsPort::class.java) shouldBe true
        companies.isExposed(LinkedApplicationsPort::class.java) shouldBe true
        companies.isExposed(CompanyRepositoryPort::class.java) shouldBe false
        module("applications").getDirectDependencies(modules).containsModuleNamed("companies") shouldBe true
        companies.getDirectDependencies(modules).containsModuleNamed("applications") shouldBe false
    }

    @Test
    fun `applications exposes only its API and SPI to the tasks context, which depends on it (#85, #87)`() {
        val applications = module("applications")

        applications.namedInterfaces.getByName("api").isPresent shouldBe true
        applications.namedInterfaces.getByName("spi").isPresent shouldBe true
        applications.isExposed(FindGhostedCandidatesPort::class.java) shouldBe true
        applications.isExposed(LinkedTasksPort::class.java) shouldBe true
        applications.isExposed(ApplicationSettings::class.java) shouldBe false
        applications.isExposed(ChangeApplicationStatusPort::class.java) shouldBe false
        module("tasks").getDirectDependencies(modules).containsModuleNamed("applications") shouldBe true
        applications.getDirectDependencies(modules).containsModuleNamed("tasks") shouldBe false
    }

    private fun module(name: String): ApplicationModule =
        modules.getModuleByName(name).orElseThrow { AssertionError("No application module '$name'") }
}
