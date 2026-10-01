// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.LinkApplicationContactsUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `set_application_contacts` over the real use case with a mocked repository: arguments in, results out. */
class SetApplicationContactsToolTest {
    private val applications = mockk<ApplicationRepositoryPort>()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val tool =
        SetApplicationContactsTool(
            LinkApplicationContactsUseCase(applications, changelog, ToolTestPorts.transactions, ToolTestPorts.clock),
        )
    private val applicationId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val contactId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val application =
        Application.create(
            ApplicationId(applicationId),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID())),
            Instant.parse("2026-09-30T08:00:00Z"),
        )

    @Test
    fun `links exactly the given contacts as the caller, based on the given version`() {
        val replaced = slot<Application>()
        every { applications.findById(any()) } returns ApplicationStoreResult.Success(application)
        every { applications.replaceContacts(capture(replaced)) } returns ApplicationStoreResult.Success(Unit)

        val answer = tool.call(call("id" to "$applicationId", "version" to 0, "contactIds" to listOf("$contactId")))

        replaced.captured.contacts shouldBe setOf(ContactRef(contactId))
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationDetailResult>()
        result.contactIds shouldBe listOf(contactId)
    }

    @Test
    fun `an unchanged set writes nothing`() {
        every { applications.findById(any()) } returns ApplicationStoreResult.Success(application)

        tool
            .call(call("id" to applicationId.toString(), "version" to 0, "contactIds" to emptyList<String>()))
            .shouldBeInstanceOf<ToolAnswer.Result>()

        verify(exactly = 0) { applications.replaceContacts(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `failures become tool errors that name the argument and carry no stored content`() {
        val arguments = arrayOf("id" to applicationId.toString(), "version" to 0, "contactIds" to listOf("$contactId"))
        every { applications.findById(any()) } returns ApplicationStoreResult.Success(application)

        every { applications.replaceContacts(any()) } returns ApplicationStoreResult.NotFound
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"
        every { applications.replaceContacts(any()) } returns ApplicationStoreResult.StorageFailure("link")
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
        tool
            .call(call("id" to applicationId.toString(), "version" to 3, "contactIds" to emptyList<String>()))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .code shouldBe "version-conflict"
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `too many contacts are reported on contactIds`() {
        every { applications.findById(any()) } returns ApplicationStoreResult.Success(application)
        val many = List(Application.MAX_CONTACTS + 1) { UUID.randomUUID().toString() }

        tool.call(call("id" to applicationId.toString(), "version" to 0, "contactIds" to many)) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The contacts cannot be linked.",
                listOf(ArgumentProblem("contactIds", "too-many")),
            )
    }

    @Test
    fun `arguments of the wrong shape never reach the use case`() {
        shouldThrow<InvalidToolArgument> { tool.call(call("version" to 0, "contactIds" to emptyList<String>())) }
            .argument shouldBe "id"
        val noVersion = call("id" to "$applicationId", "contactIds" to emptyList<String>())
        shouldThrow<InvalidToolArgument> { tool.call(noVersion) }.argument shouldBe "version"
        shouldThrow<InvalidToolArgument> {
            tool.call(call("id" to "$applicationId", "version" to 0, "contactIds" to listOf("nope")))
        }.argument shouldBe "contactIds"
        verify(exactly = 0) { applications.findById(any()) }
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)
}
