// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.application.FilterToolResultUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class McpToolSpecificationsTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val protocol = JacksonMcpJsonMapper(JsonMapper.builder().build())
    private val chat = McpTransportContext.create(mapOf("jofi.actor" to Actor.Ai))
    private var seenCaller: Actor? = null

    private val echo =
        object : McpTool {
            override val name = "echo"
            override val description = "Echoes its text."
            override val inputSchema = """{"type":"object","properties":{"text":{"type":"string"}}}"""
            override val readOnly = true

            override fun call(call: ToolCall): ToolAnswer {
                seenCaller = call.caller
                return when (val text = call.arguments.text("text")) {
                    "fail" -> error("boom with a secret")
                    "refuse" -> ToolAnswer.Error("not-found", "Nothing here.")
                    else -> ToolAnswer.Result(mapOf("echo" to text, "page" to Untrusted(mapOf("title" to text))))
                }
            }
        }

    private fun specifications(visibility: AiVisibilityResult = AiVisibilityResult.Known(NeverSendRules.NONE)) =
        McpToolSpecifications(json, protocol, FilterToolResultUseCase(fixed(visibility)))

    @Test
    fun `the definition carries name, description, schema and the read-only hint`() {
        val tool = specifications().of(echo).tool()

        tool.name() shouldBe "echo"
        tool.description() shouldBe "Echoes its text."
        tool.annotations().readOnlyHint() shouldBe true
        tool.inputSchema()["type"] shouldBe "object"
    }

    @Test
    fun `a result is JSON, third-party content is marked untrusted, and the caller comes from the context`() {
        val result = specifications().call(echo, chat, mapOf("text" to "hi", "caller" to "User"))

        result.isError shouldBe false
        textOf(result) shouldBe
            """{"echo":"hi","page":{"content":{"title":"hi"},"notice":"${Untrusted.NOTICE}","trust":"untrusted"}}"""
        seenCaller shouldBe Actor.Ai
    }

    @Test
    fun `flagged values never leave in a result`() {
        val flagged = AiVisibilityResult.Known(NeverSendRules(emptyMap(), setOf(FlaggedValue("Musterstraße 5"))))

        val result = specifications(flagged).call(echo, chat, mapOf("text" to "I live at Musterstraße 5"))

        textOf(result) shouldNotContain "Musterstraße"
        textOf(result) shouldBe
            """{"echo":"I live at [withheld]","page":{"content":{"title":"I live at [withheld]"},""" +
            """"notice":"${Untrusted.NOTICE}","trust":"untrusted"}}"""
    }

    @Test
    fun `when the flags cannot be read nothing of the result leaves`() {
        val result = specifications(AiVisibilityResult.Unavailable("down")).call(echo, chat, mapOf("text" to "hi"))

        result.isError shouldBe true
        textOf(result) shouldBe
            """{"code":"privacy-filter-failed","message":"The result was withheld: the privacy flags could """ +
            """not be read.","problems":[]}"""
    }

    @Test
    fun `errors are tool errors with a code, also for bad arguments and unexpected failures`() {
        val specifications = specifications()

        val refused = specifications.call(echo, chat, mapOf("text" to "refuse"))
        val badArgument = specifications.call(echo, chat, mapOf("text" to 42))
        val failed = specifications.call(echo, chat, mapOf("text" to "fail"))

        listOf(refused, badArgument, failed).map { it.isError } shouldBe listOf(true, true, true)
        textOf(refused) shouldBe """{"code":"not-found","message":"Nothing here.","problems":[]}"""
        textOf(badArgument) shouldBe
            """{"code":"invalid-arguments","message":"An argument has the wrong type or format.",""" +
            """"problems":[{"argument":"text","problem":"invalid"}]}"""
        textOf(failed) shouldBe """{"code":"internal-error","message":"The tool failed unexpectedly.","problems":[]}"""
    }

    @Test
    fun `a call without an authenticated caller runs nothing`() {
        val result = specifications().call(echo, McpTransportContext.EMPTY, mapOf("text" to "hi"))

        result.isError shouldBe true
        textOf(result) shouldBe
            """{"code":"unauthenticated","message":"The call carries no authenticated caller.","problems":[]}"""
        seenCaller shouldBe null
    }

    private fun textOf(result: McpSchema.CallToolResult): String =
        (result.content().single() as McpSchema.TextContent).text()

    private fun fixed(answer: AiVisibilityResult) =
        object : AiVisibilityPort {
            override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult = answer
        }
}
