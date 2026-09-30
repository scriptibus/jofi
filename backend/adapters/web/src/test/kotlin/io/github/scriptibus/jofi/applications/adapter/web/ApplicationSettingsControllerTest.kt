// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetApplicationSettingsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationSettingsUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The settings endpoints (#85) over the real use cases with a mocked store: the defaults, a change recorded as the
 * user, and the 400 and 409. Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(ApplicationSettingsController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationSettingsControllerTest.UseCases::class)
class ApplicationSettingsControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    class Ports {
        val settings = mockk<ApplicationSettingsRepositoryPort>()
        val changelog = mockk<ChangelogPort>()
        val transactions =
            object : TransactionPort {
                override fun <T> inTransaction(
                    commitIf: (T) -> Boolean,
                    work: () -> T,
                ): T = work()
            }
    }

    @TestConfiguration
    class UseCases {
        @Bean
        fun ports() = Ports()

        @Bean
        fun get(ports: Ports) = GetApplicationSettingsUseCase(ports.settings)

        @Bean
        fun update(ports: Ports) =
            UpdateApplicationSettingsUseCase(ports.settings, ports.changelog, ports.transactions, CLOCK)
    }

    private val path = "/api/applications/settings"

    @BeforeEach
    fun defaults() {
        clearMocks(ports.settings, ports.changelog)
        every { ports.settings.find() } returns ApplicationStoreResult.Success(ApplicationSettings.DEFAULT)
        every { ports.settings.update(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `reading answers the defaults until the user changes them`() {
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo("""{"ghostedAfterWeeks":14,"followUpAfterDays":14,"version":0,"updatedAt":null}""")
    }

    @Test
    fun `changing answers the new version, stored and recorded as the user`() {
        put("""{"ghostedAfterWeeks":10,"followUpAfterDays":7,"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """{"ghostedAfterWeeks":10,"followUpAfterDays":7,"version":1,"updatedAt":"2026-09-30T08:00:00Z"}""",
            )
        verify { ports.settings.update(ApplicationSettings(ApplicationSettings.Values(10, 7), 1, NOW)) }
        verify { ports.changelog.append(match { it.actor == Actor.User && it.change.fieldChanges.size == 2 }) }
    }

    @Test
    fun `values out of range are a 400 naming them, a stale version a 409`() {
        put("""{"ghostedAfterWeeks":53,"followUpAfterDays":0,"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"violations":[{"field":"ghostedAfterWeeks","problem":"OUT_OF_RANGE"},
                   {"field":"followUpAfterDays","problem":"OUT_OF_RANGE"}]}""",
            )
        put("""{"ghostedAfterWeeks":10,"followUpAfterDays":7,"basedOnVersion":4}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.VERSION_CONFLICT)
        put("""{"ghostedAfterWeeks":10,"basedOnVersion":0}""").assertThat().hasStatus(400)
        verify(exactly = 0) { ports.settings.update(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    private fun put(body: String): MockMvcTester.MockMvcRequestBuilder =
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-30T08:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
