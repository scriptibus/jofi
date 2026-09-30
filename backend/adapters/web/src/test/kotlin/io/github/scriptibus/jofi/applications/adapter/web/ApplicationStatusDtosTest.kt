// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.StatusChangeInput
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApplicationStatusDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val id = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))

    @Test
    fun `a status change request becomes domain input unchanged, validation is the domain's job`() {
        ChangeApplicationStatusRequest(PipelineStatus.DECLINED, " Too far ", DeclineReasonCategory.LOCATION, 3)
            .toInput() shouldBe StatusChangeInput(ApplicationStatus.DECLINED, " Too far ", DeclineCategory.LOCATION)
        ChangeApplicationStatusRequest(PipelineStatus.APPLIED, basedOnVersion = 0).toInput() shouldBe
            StatusChangeInput(ApplicationStatus.APPLIED)
    }

    @Test
    fun `every actor keeps its kind and name`() {
        listOf(
            Actor.User to ChangeActorDto(ChangeActorKind.USER, null),
            Actor.Ai to ChangeActorDto(ChangeActorKind.AI, null),
            Actor.Scanner("Feed") to ChangeActorDto(ChangeActorKind.SCANNER, "Feed"),
            Actor.ExternalClient("Claude") to ChangeActorDto(ChangeActorKind.EXTERNAL_CLIENT, "Claude"),
            Actor.System("ghosted") to ChangeActorDto(ChangeActorKind.SYSTEM, "ghosted"),
        ).forEach { (actor, dto) -> ChangeActorDto.from(actor) shouldBe dto }
    }

    private val history =
        listOf(
            StatusChange(id, null, ApplicationStatus.DISCOVERED, null, null, Actor.Scanner("Feed"), at),
            StatusChange(
                id,
                ApplicationStatus.DISCOVERED,
                ApplicationStatus.DECLINED,
                "Secret reason",
                DeclineCategory.SALARY,
                Actor.User,
                at.plusSeconds(60),
            ),
        )

    @Test
    fun `the history becomes a response, oldest first, with every field`() {
        StatusHistoryResponse.from(history) shouldBe
            StatusHistoryResponse(
                listOf(
                    StatusChangeResponse(
                        null,
                        PipelineStatus.DISCOVERED,
                        null,
                        null,
                        ChangeActorDto(ChangeActorKind.SCANNER, "Feed"),
                        at,
                    ),
                    StatusChangeResponse(
                        PipelineStatus.DISCOVERED,
                        PipelineStatus.DECLINED,
                        "Secret reason",
                        DeclineReasonCategory.SALARY,
                        ChangeActorDto(ChangeActorKind.USER, null),
                        at.plusSeconds(60),
                    ),
                ),
            )
    }

    @Test
    fun `status DTOs print no reason`() {
        val response = StatusHistoryResponse.from(history)
        listOf(
            response,
            response.changes[1],
            ChangeApplicationStatusRequest(PipelineStatus.DECLINED, "Secret reason", DeclineReasonCategory.OTHER, 1),
        ).forEach { it.toString() shouldNotContain "Secret" }
    }
}
