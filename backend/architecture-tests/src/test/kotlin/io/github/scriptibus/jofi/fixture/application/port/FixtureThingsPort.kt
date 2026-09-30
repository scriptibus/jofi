// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/** A port with one unguarded and one proof-taking destructive method. Test fixture only. */
interface FixtureThingsPort {
    fun delete(id: String)

    fun send(
        id: String,
        proof: ConfirmationResult.Confirmed,
    )
}
