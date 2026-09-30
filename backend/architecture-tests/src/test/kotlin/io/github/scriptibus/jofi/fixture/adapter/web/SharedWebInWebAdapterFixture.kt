// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations

/** Allowed: another context's web adapter using the shared web conventions. Test fixture only. */
class SharedWebInWebAdapterFixture {
    fun token(header: String?) = Confirmations.token(header)
}
