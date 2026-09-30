// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.MasterKeyState

/**
 * The master keyset in the data volume that encrypts every stored secret (ADR-0035). It is only ever
 * generated on request, never as a side effect of a missing file. Implementations never throw and
 * never log key material.
 */
interface MasterKeyPort {
    /** Whether the keyset file is there and readable. */
    fun state(): MasterKeyState

    /** Generates a new keyset unless one exists; the state afterwards. */
    fun generate(): MasterKeyState

    /** A fresh check value: proves later that the same keyset is in place. `null` without a keyset. */
    fun newCheckValue(): ByteArray?

    /** Whether [checkValue] was made with the current keyset. */
    fun verifies(checkValue: ByteArray): Boolean
}
