// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component

/** `create_application`: adds an application in its first status; the changelog records the caller as the actor. */
@Component
class CreateApplicationTool(
    private val createApplication: CreateApplicationUseCase,
) : McpTool {
    override val name = "create_application"
    override val readOnly = false
    override val description =
        "Add a job application by hand. Required: `companyId` (an existing company: find it with " +
            "search_companies or create it first) and `posting.title`. Everything else may be left out or null " +
            "(`notes` and `languageAndTone` too). The arguments have the shape of get_application's answer, " +
            "without `id`, `version` and `readOnly`: `posting` {title, location}, `notes` {portalNotes, " +
            "payEstimateBasis, offer: {bonus, benefits, noticePeriod}}, `payBand`, `offer`, `languageAndTone` " +
            "and the typed fields. `notes.payEstimateBasis` belongs to an ESTIMATED pay band only; an offer's " +
            "typed details are in `offer`, its texts in `notes.offer`. A value that shows " +
            "[withheld] is hidden from you and refused (withheld-value). Answers the new application with its id " +
            "and version, in the shape of get_application; the posting, notes and language tags in it are marked " +
            "untrusted."
    override val inputSchema = ApplicationToolSchema.schema(update = false)

    override fun call(call: ToolCall): ToolAnswer =
        refusal(call)
            ?: when (val result = create(call)) {
                is ApplicationResult.Success -> ToolAnswer.Result(ApplicationDetailResult.from(result.value))
                is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
            }

    /** A withheld marker, or arguments that contradict each other. */
    private fun refusal(call: ToolCall): ToolAnswer.Error? =
        call.arguments.withheldPath()?.let(ToolProblems::withheldValue)
            ?: ApplicationToolInput
                .conflicts(call.arguments, update = false)
                .takeIf { it.isNotEmpty() }
                ?.let(ApplicationToolErrors::contradiction)

    // A named method, not a lambda: the architecture rule sees the one use case call here.
    private fun create(call: ToolCall) = createApplication.execute(ApplicationToolInput.of(call.arguments), call.caller)
}
