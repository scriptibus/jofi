// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables

/** Allowed: another context's persistence adapter using the generated jOOQ code. Test fixture only. */
class JooqInPersistenceAdapterFixture {
    val tables: Class<*> = Tables::class.java
}
