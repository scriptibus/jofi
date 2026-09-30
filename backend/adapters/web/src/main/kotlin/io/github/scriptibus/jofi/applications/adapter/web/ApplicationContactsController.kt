// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.LinkApplicationContactsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The contacts linked to an application (#90), apart from [ApplicationController] so neither takes more use
 * cases than the constructor limit allows. The applications linked to a contact are the list filtered by
 * `contactId` ([ApplicationListController]).
 */
@RestController
@RequestMapping("/api/applications")
class ApplicationContactsController(
    private val linkContacts: LinkApplicationContactsUseCase,
) {
    /**
     * Links exactly the given contacts (replacing the linked set; duplicates count once); 400 on `contactIds`
     * for more than 50 or one that does not exist, 409 if `basedOnVersion` is stale.
     */
    @PutMapping("/{id}/contacts")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun linkApplicationContacts(
        @PathVariable id: UUID,
        @RequestBody request: ApplicationContactsRequest,
    ): ApplicationResponse =
        ApplicationResponse.from(
            linkContacts
                .execute(
                    ApplicationId(id),
                    request.contactIds.mapTo(mutableSetOf(), ::ContactRef),
                    request.basedOnVersion,
                    Actor.User,
                ).orThrow(),
        )
}
