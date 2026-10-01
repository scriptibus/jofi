// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetPostingImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport

/** One posting import, for polling its status (#96). Reads only. */
class GetPostingImportUseCase(
    private val imports: PostingImportRepositoryPort,
) : GetPostingImportPort {
    override fun execute(id: ImportId): ApplicationResult<PostingImport> = imports.findById(id).importResult()
}
