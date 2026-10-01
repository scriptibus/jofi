// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException

/** What happened when the server asked the client to confirm an action. */
enum class HumanAnswer {
    CONFIRMED,
    DECLINED,

    /** The client did not answer within the confirmation timeout. */
    TIMED_OUT,

    /** Asking failed (the filter, the transport or the client): nothing may run. */
    UNAVAILABLE,
}

/** The right to wait for one confirmation, taken before the first step so no unusable token is issued. */
sealed interface Reservation {
    /** The client cannot be asked (no form elicitation, no session): nothing may run. */
    data object Unsupported : Reservation

    /** This session, or the server as a whole, already has the allowed number of confirmations waiting. */
    data object Busy : Reservation

    /** Held while the tool call runs; [close] gives the slot back. */
    class Granted(
        private val release: () -> Unit,
    ) : Reservation,
        AutoCloseable {
        override fun close() = release()
    }
}

/**
 * Asks the client to confirm an action (ADR-0039, "MCP and the built-in chat"). The model never sees the
 * confirmation token: the tool keeps it server-side and uses it only after the client reported a yes. The
 * default is unsupported; only an implementation that really can ask says otherwise.
 */
interface HumanConfirmer {
    fun reserve(): Reservation = Reservation.Unsupported

    /** Passes stored text through the "never send to AI" filter; null means it failed, so nothing is asked. */
    fun screen(stored: String): String? = null

    fun ask(message: String): HumanAnswer

    companion object {
        /** For callers that cannot reach a human: every confirmation is unavailable. */
        val NONE: HumanConfirmer =
            object : HumanConfirmer {
                override fun ask(message: String) = HumanAnswer.UNAVAILABLE
            }

        /** A confirmer that is always ready and answers with [answer]; for tests of tools and helpers. */
        fun answering(answer: (String) -> HumanAnswer): HumanConfirmer =
            object : HumanConfirmer {
                override fun reserve(): Reservation = Reservation.Granted {}

                override fun screen(stored: String): String = stored

                override fun ask(message: String) = answer(message)
            }
    }
}

/**
 * MCP elicitation (form mode) on the exchange of the running tool call: the client shows the message and one
 * checkbox and reports the answer. The server cannot prove that a person answered, only that the client did.
 * Stored text and the whole message pass [filter] (the "never send to AI" filter, ADR-0053; null means it
 * failed, so nothing is asked). Waiting calls hold a thread, so [slots] allows one per MCP session and a few
 * for the whole server; the slot is taken in [reserve], before any token exists. A client without form
 * elicitation, a transport error or a refused message is [HumanAnswer.UNAVAILABLE], a missing answer
 * [HumanAnswer.TIMED_OUT]; only an explicit "accept" with the box checked (a real boolean) is
 * [HumanAnswer.CONFIRMED].
 */
class ElicitingConfirmer(
    private val exchange: McpSyncServerExchange,
    private val slots: ConfirmationSlots,
    private val filter: (String) -> String?,
) : HumanConfirmer {
    override fun reserve(): Reservation {
        val session = exchange.sessionId()
        return when {
            session == null || !supportsForms() -> Reservation.Unsupported
            else -> slots.tryReserve(session)?.let { Reservation.Granted(it::close) } ?: Reservation.Busy
        }
    }

    override fun screen(stored: String): String? = filter(stored)

    @Suppress("TooGenericExceptionCaught") // The SDK throws unchecked transport and protocol errors.
    override fun ask(message: String): HumanAnswer {
        val text = filter(message)
        return when {
            text == null || !supportsForms() -> {
                HumanAnswer.UNAVAILABLE
            }

            else -> {
                try {
                    answerOf(exchange.createElicitation(McpSchema.ElicitFormRequest.builder(text, SCHEMA).build()))
                } catch (failure: Exception) {
                    failed(failure)
                }
            }
        }
    }

    private fun failed(failure: Exception): HumanAnswer {
        var cause: Throwable = failure
        var depth = 0
        while (cause.cause != null && cause.cause !== cause &&
            depth++ < MAX_CAUSE_DEPTH
        ) {
            cause = cause.cause as Throwable
        }
        // Class names only: messages may carry stored text.
        log.warn("MCP confirmation not answered: {}", cause.javaClass.name)
        return if (cause is TimeoutException) HumanAnswer.TIMED_OUT else HumanAnswer.UNAVAILABLE
    }

    private fun supportsForms(): Boolean {
        val elicitation = exchange.clientCapabilities?.elicitation() ?: return false
        // An empty `elicitation: {}` means form mode; a client that only supports URL mode cannot show a form.
        return elicitation.form() != null || elicitation.url() == null
    }

    private fun answerOf(result: McpSchema.ElicitResult?): HumanAnswer =
        when {
            result == null || result.action() != McpSchema.ElicitResult.Action.ACCEPT -> HumanAnswer.DECLINED
            result.content()?.get(FIELD) == true -> HumanAnswer.CONFIRMED
            else -> HumanAnswer.DECLINED
        }

    private companion object {
        const val FIELD = "confirm"
        const val MAX_CAUSE_DEPTH = 10
        val log: Logger = LoggerFactory.getLogger(ElicitingConfirmer::class.java)
        val SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        FIELD to
                            mapOf(
                                "type" to "boolean",
                                "title" to "Confirm",
                                "description" to "Check to confirm, or decline the request.",
                                "default" to false,
                            ),
                    ),
                "required" to listOf(FIELD),
            )
    }
}
