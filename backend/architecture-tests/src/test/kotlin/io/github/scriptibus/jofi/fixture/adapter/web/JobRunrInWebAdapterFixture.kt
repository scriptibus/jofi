// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import org.jobrunr.storage.StorageProvider

/** Known-bad: a web adapter reading the job store directly instead of through `JobLogPort`. */
class JobRunrInWebAdapterFixture(
    val storage: StorageProvider,
)
