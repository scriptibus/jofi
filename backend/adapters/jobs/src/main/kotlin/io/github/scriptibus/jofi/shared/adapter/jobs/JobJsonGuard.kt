// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.json.JsonMapper as JacksonJsonMapper

/**
 * First layer of the job store allowlist (ADR-0038): checks the stored JSON of a job or recurring job
 * as plain text, with a Jackson mapper that resolves no type ids, BEFORE JobRunr's mapper loads any
 * class the JSON names. JobRunr initialises job parameter classes (`Class.forName(name, true, ...)`)
 * and resolves `@class` ids before its type validator runs, so the check must come first.
 */
internal object JobJsonGuard {
    private val tree: JacksonJsonMapper = JacksonJsonMapper.builder().build()

    private val HANDLER = JofiJobRequestHandler::class.java.name
    private val REQUEST = JofiJobRequest::class.java.name

    /** Every type id JobRunr writes for the jobs Jofi creates: its job states, metadata and containers. */
    private val TYPE_IDS: Set<String> =
        setOf(
            "org.jobrunr.jobs.states.ScheduledState",
            "org.jobrunr.jobs.states.EnqueuedState",
            "org.jobrunr.jobs.states.ProcessingState",
            "org.jobrunr.jobs.states.SucceededState",
            "org.jobrunr.jobs.states.FailedState",
            "org.jobrunr.jobs.states.DeletedState",
            // Job metadata JobRunr writes itself (dashboard log lines and progress).
            "org.jobrunr.jobs.context.JobDashboardLogger\$JobDashboardLogLines",
            "org.jobrunr.jobs.context.JobDashboardProgressBar\$JobDashboardProgress",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.CopyOnWriteArrayList",
            REQUEST,
        )

    /** Wrapper-array type ids (`["java.util.X", ...]`) look like a qualified class name. */
    private val CLASS_NAME = Regex("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")

    /** The JSON as a tree, or null if it is not a JSON object. */
    fun parse(json: String): ObjectNode? =
        try {
            tree.readTree(json) as? ObjectNode
        } catch (_: JacksonException) {
            null
        }

    /** Why [root] is not a job Jofi wrote, or null if it is. The reason names classes, never data. */
    fun problem(root: ObjectNode): String? = foreignTypeId(root)?.let { "type id $it" } ?: detailsProblem(root)

    /** Whether the only problem is in `jobDetails` (then JobRunr may read the rest safely). */
    fun hasForeignTypeIds(root: ObjectNode): Boolean = foreignTypeId(root) != null

    /** [root] with its job details replaced by [details] and its name by the rejected type. */
    fun withDetails(
        root: ObjectNode,
        details: JsonNode,
    ): String {
        val copy = root.deepCopy()
        copy.set("jobDetails", details)
        copy.put("jobName", JofiJobRequest.REJECTED_TYPE)
        return tree.writeValueAsString(copy)
    }

    fun toTree(json: String): JsonNode = tree.readTree(json)

    private fun detailsProblem(root: ObjectNode): String? {
        val details = root.get("jobDetails") as? ObjectNode ?: return "no job details"
        return when {
            details.text("className") != HANDLER -> "handler ${details.text("className")}"
            details.text("methodName") != "run" -> "method ${details.text("methodName")}"
            details.text("staticFieldName") != null -> "static field"
            else -> parameterProblem(details.get("jobParameters"))
        }
    }

    private fun parameterProblem(parameters: JsonNode?): String? {
        val parameter =
            parameters?.takeIf { it.isArray && it.size() == 1 }?.get(0) ?: return "not exactly one parameter"
        val actual = parameter.text("actualClassName")
        return when {
            parameter.text("className") != REQUEST -> "parameter ${parameter.text("className")}"
            actual != null && actual != REQUEST -> "parameter $actual"
            else -> null
        }
    }

    private fun foreignTypeId(node: JsonNode): String? =
        when {
            node.isObject -> {
                node
                    .get("@class")
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.takeUnless { it in TYPE_IDS }
                    ?: node.values().firstNotNullOfOrNull(::foreignTypeId)
            }

            node.isArray -> {
                wrapperTypeId(node) ?: node.values().firstNotNullOfOrNull(::foreignTypeId)
            }

            else -> {
                null
            }
        }

    private fun wrapperTypeId(array: JsonNode): String? {
        val first = array.get(0)
        val isWrapper = array.size() == 2 && first.isString && CLASS_NAME.matches(first.asString())
        return if (isWrapper) first.asString().takeUnless { it in TYPE_IDS } else null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()
}
