// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Moving an application through the pipeline (ADR-0044): history entry, event, decline reason, version. */
class ApplicationStatusChangeTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val later = at.plusSeconds(60)
    private val id = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val details = ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID()))
    private val discovered = Application.create(id, details, at)
    private val scanner = Actor.Scanner("Feed")

    private fun Application.moved(
        request: StatusChangeRequest,
        actor: Actor = Actor.User,
    ): StatusTransition.Changed = changeStatus(request, actor, later).shouldBeInstanceOf<StatusTransition.Changed>()

    private fun StatusChangeInput.valid(): StatusChangeRequest =
        validate().shouldBeInstanceOf<ApplicationValidation.Valid<StatusChangeRequest>>().value

    @Test
    fun `a new application is discovered, and its first history entry says so`() {
        discovered.status shouldBe ApplicationStatus.DISCOVERED
        discovered.declineReason shouldBe null
        StatusChange.initial(discovered, scanner) shouldBe
            StatusChange(id, null, ApplicationStatus.DISCOVERED, null, null, scanner, at)
    }

    @Test
    fun `a move yields a new version, a timestamped history entry with actor and reason, and the event`() {
        val moved = discovered.moved(StatusChangeRequest(ApplicationStatus.APPLIED, "Sent via portal"), scanner)

        moved.application.status shouldBe ApplicationStatus.APPLIED
        moved.application.version shouldBe discovered.version + 1
        moved.application.updatedAt shouldBe later
        moved.application.details shouldBe discovered.details
        moved.change shouldBe
            StatusChange(
                id,
                ApplicationStatus.DISCOVERED,
                ApplicationStatus.APPLIED,
                "Sent via portal",
                null,
                scanner,
                later,
            )
        moved.event shouldBe
            ApplicationStatusChanged(id, ApplicationStatus.DISCOVERED, ApplicationStatus.APPLIED, scanner, later)
    }

    @Test
    fun `declining sets the decline reason, which the history entry keeps when reopening clears it`() {
        val declined =
            discovered.moved(StatusChangeRequest(ApplicationStatus.DECLINED, "Too low", DeclineCategory.SALARY))
        declined.application.declineReason shouldBe DeclineReason(DeclineCategory.SALARY, "Too low")
        declined.change.declineCategory shouldBe DeclineCategory.SALARY
        declined.change.reason shouldBe "Too low"

        val reopened = declined.application.moved(StatusChangeRequest(ApplicationStatus.SHORTLISTED))

        reopened.application.declineReason shouldBe null
        reopened.application.version shouldBe declined.application.version + 1
        reopened.change.declineCategory shouldBe null
    }

    @Test
    fun `a rejection sets the decline reason too, and moving to it again corrects the reason`() {
        val applied = discovered.moved(StatusChangeRequest(ApplicationStatus.APPLIED)).application
        val rejected =
            applied
                .moved(
                    StatusChangeRequest(ApplicationStatus.REJECTED, null, DeclineCategory.NO_REASON_GIVEN),
                ).application
        rejected.declineReason shouldBe DeclineReason(DeclineCategory.NO_REASON_GIVEN)

        val corrected =
            rejected.moved(
                StatusChangeRequest(ApplicationStatus.REJECTED, "Filled internally", DeclineCategory.POSITION_FILLED),
            )

        corrected.application.declineReason shouldBe DeclineReason(DeclineCategory.POSITION_FILLED, "Filled internally")
        corrected.change.from shouldBe ApplicationStatus.REJECTED
    }

    @Test
    fun `moving to the current status with the same reason changes nothing`() {
        discovered.changeStatus(StatusChangeRequest(ApplicationStatus.DISCOVERED, "Again"), Actor.User, later) shouldBe
            StatusTransition.Unchanged
        val request = StatusChangeRequest(ApplicationStatus.DECLINED, "Too far", DeclineCategory.LOCATION)
        discovered.moved(request).application.changeStatus(request, Actor.User, later) shouldBe
            StatusTransition.Unchanged
    }

    @Test
    fun `a move the matrix does not allow is refused and changes nothing`() {
        discovered.changeStatus(StatusChangeRequest(ApplicationStatus.ACCEPTED), Actor.Ai, later) shouldBe
            StatusTransition.NotAllowed(ApplicationStatus.DISCOVERED, ApplicationStatus.ACCEPTED)
        discovered.changeStatus(
            StatusChangeRequest(ApplicationStatus.REJECTED, null, DeclineCategory.SKILLS),
            Actor.Ai,
            later,
        ) shouldBe StatusTransition.NotAllowed(ApplicationStatus.DISCOVERED, ApplicationStatus.REJECTED)
    }

    @Test
    fun `input is normalized, declined and rejected need a category, other statuses take none`() {
        StatusChangeInput(ApplicationStatus.APPLIED, "  Cafe\u0301 ").valid() shouldBe
            StatusChangeRequest(ApplicationStatus.APPLIED, "Caf\u00e9")
        StatusChangeInput(ApplicationStatus.APPLIED, " ").valid().reason shouldBe null
        StatusChangeInput(
            ApplicationStatus.DECLINED,
            declineCategory = DeclineCategory.ROLE,
        ).valid().declineReason shouldBe
            DeclineReason(DeclineCategory.ROLE)

        StatusChangeInput(ApplicationStatus.REJECTED, "x".repeat(StatusChange.MAX_REASON_LENGTH + 1))
            .validate()
            .shouldBeInstanceOf<ApplicationValidation.Invalid>()
            .violations shouldContainExactlyInAnyOrder
            listOf(
                ApplicationViolation(ApplicationField.STATUS_REASON, ApplicationProblem.TOO_LONG),
                ApplicationViolation(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.REQUIRED),
            )
        StatusChangeInput(ApplicationStatus.OFFER, "a\u0000b", DeclineCategory.OTHER)
            .validate()
            .shouldBeInstanceOf<ApplicationValidation.Invalid>()
            .violations shouldContainExactlyInAnyOrder
            listOf(
                ApplicationViolation(ApplicationField.STATUS_REASON, ApplicationProblem.INVALID_CHARACTER),
                ApplicationViolation(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.NOT_APPLICABLE),
            )
    }

    @Test
    fun `a reason at exactly its limit is valid`() {
        val reason = "r".repeat(StatusChange.MAX_REASON_LENGTH)

        StatusChangeInput(ApplicationStatus.DECLINED, reason, DeclineCategory.OTHER).valid().declineReason shouldBe
            DeclineReason(DeclineCategory.OTHER, reason)
    }

    @Test
    fun `the invariants hold a decline reason exactly while declined or rejected`() {
        shouldThrow<IllegalArgumentException> { discovered.copy(declineReason = DeclineReason(DeclineCategory.OTHER)) }
        shouldThrow<IllegalArgumentException> { discovered.copy(status = ApplicationStatus.DECLINED) }
        shouldThrow<IllegalArgumentException> { StatusChangeRequest(ApplicationStatus.DECLINED) }
        shouldThrow<IllegalArgumentException> {
            StatusChangeRequest(ApplicationStatus.APPLIED, declineCategory = DeclineCategory.OTHER)
        }
        shouldThrow<IllegalArgumentException> {
            StatusChange(id, null, ApplicationStatus.REJECTED, null, null, Actor.User, at)
        }
        shouldThrow<IllegalArgumentException> {
            StatusChange(id, null, ApplicationStatus.APPLIED, " reason", null, Actor.User, at)
        }
    }

    @Test
    fun `nothing prints the reason`() {
        val input = StatusChangeInput(ApplicationStatus.DECLINED, "Secret", DeclineCategory.OTHER)
        val declined = discovered.moved(input.valid())

        listOf(input, input.valid(), declined.change, declined.application, declined.event).forEach {
            it.toString() shouldNotContain "Secret"
        }
    }
}
