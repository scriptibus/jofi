// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations

/** Known-bad: a persistence adapter using the shared web conventions. Test fixture only. */
class SharedWebInPersistenceAdapterFixture {
    fun token(header: String?) = Confirmations.token(header)
}
