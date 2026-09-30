// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

/**
 * Who caused a change. Every mutation is recorded with its actor (spec §2 principle 3, §13), so
 * the user can always tell their own edits from AI, scanner, external-client and automatic ones.
 */
sealed interface Actor {
    /** The single Jofi user, acting through the UI. */
    data object User : Actor

    /** The built-in AI (chat, scoring, proposals), acting on the user's behalf. */
    data object Ai : Actor

    /** A job scanner, identified by its configured name. */
    data class Scanner(
        val name: String,
    ) : Actor {
        init {
            requireName(name)
        }
    }

    /** An external MCP or API client, identified by the name it reported. */
    data class ExternalClient(
        val name: String,
    ) : Actor {
        init {
            requireName(name)
        }
    }

    /** An automatic rule or background job, identified by its name. */
    data class System(
        val name: String,
    ) : Actor {
        init {
            requireName(name)
        }
    }
}

private fun requireName(name: String) {
    require(name.isNotBlank()) { "An actor name must not be blank" }
}
