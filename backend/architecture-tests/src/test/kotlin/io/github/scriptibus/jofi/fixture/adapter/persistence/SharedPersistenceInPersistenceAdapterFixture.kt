// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.TransactionAdapter

/** Allowed: another context's persistence adapter using the shared persistence code. Test fixture only. */
class SharedPersistenceInPersistenceAdapterFixture {
    val shared: Class<*> = TransactionAdapter::class.java
}
