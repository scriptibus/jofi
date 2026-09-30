// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** Known-bad: a `@DeleteMapping` without the confirmation header. Test fixture only. */
@RestController
@RequestMapping("/api/fixture/things")
class UnconfirmedDeleteFixtureController {
    @DeleteMapping("/{id}")
    fun deleteThing(
        @PathVariable id: String,
    ): String = id
}

/** Known-bad: a `DELETE` declared through `@RequestMapping(method = ...)`, without the header. */
@RestController
class RequestMappingDeleteFixtureController {
    @RequestMapping("/api/fixture/things/{id}", method = [RequestMethod.DELETE])
    fun deleteThing(
        @PathVariable id: String,
    ): String = id
}

/** Known-bad once its path is declared outward-facing: a `POST` that sends without the header. */
@RestController
@RequestMapping("/api/fixture")
class UnconfirmedSendFixtureController {
    @PostMapping("/send/{id}")
    fun sendThing(
        @PathVariable id: String,
    ): String = id
}

/** Known-good: a delete that follows the convention. */
@RestController
class ConfirmedDeleteFixtureController {
    @DeleteMapping("/api/fixture/things/{id}")
    fun deleteThing(
        @PathVariable id: String,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
    ): String = id + confirmation.orEmpty()
}
