// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.LinkApplicationContactsUseCase
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `set_application_contacts` over a mocked use case: arguments in, the use case's call, results out. */
class SetApplicationContactsToolTest {
    private val linkContacts = mockk<LinkApplicationContactsUseCase>()
    private val tool = SetApplicationContactsTool(linkContacts)
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
        every { linkContacts.execute(ApplicationId(applicationId), setOf(ContactRef(contactId)), 3L, Actor.Ai) } returns
            ApplicationResult.Success(application.linkContacts(setOf(ContactRef(contactId)), application.updatedAt))

        val answer =
            tool.call(
                call(
                    "id" to applicationId.toString(),
                    "version" to 3,
                    "contactIds" to listOf(contactId.toString()),
                ),
            )

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationDetailResult>()
        result.contactIds shouldBe listOf(contactId)
    }

    @Test
    fun `an empty list unlinks all`() {
        every { linkContacts.execute(any(), emptySet(), 0L, Actor.Ai) } returns ApplicationResult.Success(application)

        tool
            .call(call("id" to applicationId.toString(), "version" to 0, "contactIds" to emptyList<String>()))
            .shouldBeInstanceOf<ToolAnswer.Result>()
    }

    @Test
    fun `failures become tool errors that name the argument and carry no stored content`() {
        val arguments = arrayOf("id" to applicationId.toString(), "version" to 0, "contactIds" to emptyList<String>())

        every { linkContacts.execute(any(), any(), any(), any()) } returns
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.CONTACTS, ApplicationProblem.NOT_FOUND)),
            )
        tool.call(call(*arguments)) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The contacts cannot be linked.",
                listOf(ArgumentProblem("contactIds", "not-found")),
            )
        every { linkContacts.execute(any(), any(), any(), any()) } returns ApplicationResult.VersionConflict
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
        every { linkContacts.execute(any(), any(), any(), any()) } returns ApplicationResult.NotFound
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"
        every { linkContacts.execute(any(), any(), any(), any()) } returns ApplicationResult.StorageFailure("link")
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
    }

    @Test
    fun `arguments of the wrong shape never reach the use case`() {
        shouldThrow<InvalidToolArgument> { tool.call(call("version" to 0, "contactIds" to emptyList<String>())) }
            .argument shouldBe "id"
        shouldThrow<InvalidToolArgument> {
            tool.call(
                call(
                    "id" to applicationId.toString(),
                    "contactIds" to emptyList<String>(),
                ),
            )
        }.argument shouldBe "version"
        shouldThrow<InvalidToolArgument> {
            tool.call(call("id" to applicationId.toString(), "version" to 0, "contactIds" to listOf("nope")))
        }.argument shouldBe "contactIds"
        verify(exactly = 0) { linkContacts.execute(any(), any(), any(), any()) }
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)
}
