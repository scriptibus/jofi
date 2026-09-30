// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.application

import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy

/** Known-bad: puts a keyset back without being restore recovery. Test fixture only. */
class ReinstateKeysetWithoutRecoveryUseCase(
    private val keys: MasterKeyBackupPort,
) {
    fun execute(keyset: MasterKeysetCopy) = keys.reinstate(keyset)
}
