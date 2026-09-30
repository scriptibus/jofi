// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordHash

/** One-way password hashing (argon2id, ADR-0017). Implementations never throw and never log. */
interface PasswordHasherPort {
    /** A new salted hash of [password]. */
    fun hash(password: Password): PasswordHash

    /** Whether [password] matches [hash]; `false` for a hash it cannot read. */
    fun matches(
        password: Password,
        hash: PasswordHash,
    ): Boolean
}
