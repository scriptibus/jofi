// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.scriptibus.jofi.fixture.adapter.persistence.JooqInPersistenceAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.AiProviderPortInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.ImageIoInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.JdkHttpClientInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.JooqInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.SocketsInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.UrlClassLoaderInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.UrlHolderInWebAdapterFixture
import io.github.scriptibus.jofi.fixture.adapter.web.UrlReadInWebAdapterFixture
import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.shared.adapter.net.GuardedHttpClients
import io.github.scriptibus.jofi.shared.adapter.net.ImpostorNetAdapterFixture
import io.github.scriptibus.jofi.shared.adapter.net.OutboundHttpAdapter
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The adapter rules against known-bad and known-good fixtures (test classes, never imported by the
 * production checks), so an exemption cannot silently widen and a rule cannot silently pass.
 */
class AdapterRulesFixtureTest {
    @Test
    fun `a persistence adapter of another context may use the generated jOOQ code`() {
        val classes = ClassFileImporter().importClasses(JooqInPersistenceAdapterFixture::class.java, Tables::class.java)

        AdapterRules.adaptersAreIndependent.evaluate(classes).hasViolation() shouldBe false
    }

    @Test
    fun `a web adapter using the generated jOOQ code is still rejected`() {
        val classes = ClassFileImporter().importClasses(JooqInWebAdapterFixture::class.java, Tables::class.java)

        AdapterRules.adaptersAreIndependent.evaluate(classes).hasViolation() shouldBe true
    }

    @Test
    fun `an adapter outside setup adapter ai using AiProviderPort is rejected`() {
        val classes =
            ClassFileImporter().importClasses(AiProviderPortInWebAdapterFixture::class.java, AiProviderPort::class.java)

        AdapterRules.onlyTheAiAdapterUsesAiProviderPort.evaluate(classes).hasViolation() shouldBe true
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(
        classes = [
            JdkHttpClientInWebAdapterFixture::class,
            UrlReadInWebAdapterFixture::class,
            UrlHolderInWebAdapterFixture::class,
            ImageIoInWebAdapterFixture::class,
            UrlClassLoaderInWebAdapterFixture::class,
            SocketsInWebAdapterFixture::class,
            ImpostorNetAdapterFixture::class,
        ],
    )
    fun `network access outside the adapters net module is rejected`(fixture: Class<*>) {
        val classes = ClassFileImporter().importClasses(fixture)

        AdapterRules.onlyTheNetAdapterMakesOutboundHttpCalls.evaluate(classes).hasViolation() shouldBe true
    }

    @Test
    fun `the net adapter module itself may use its HTTP client`() {
        // Plus one unrelated adapter outside the net package, so the rule has a class to check.
        val classes =
            ClassFileImporter().importClasses(
                GuardedHttpClients::class.java,
                OutboundHttpAdapter::class.java,
                JooqInPersistenceAdapterFixture::class.java,
            )

        AdapterRules.onlyTheNetAdapterMakesOutboundHttpCalls.evaluate(classes).hasViolation() shouldBe false
    }
}
