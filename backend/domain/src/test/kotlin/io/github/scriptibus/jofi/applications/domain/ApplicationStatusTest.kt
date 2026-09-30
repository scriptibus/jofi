// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.ACCEPTED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.APPLIED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.DECLINED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.DISCOVERED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.GHOSTED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.INTERVIEWING
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.OFFER
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.PREPARING
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.REJECTED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.SHORTLISTED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.WITHDRAWN
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The transition matrix of ADR-0044, over every pair of statuses. */
class ApplicationStatusTest {
    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("everyPair")
    fun `the matrix allows exactly the moves of ADR-0044`(
        from: ApplicationStatus,
        to: ApplicationStatus,
        allowed: Boolean,
    ) {
        from.canMoveTo(to) shouldBe allowed
    }

    @Test
    fun `the matrix covers every status as a row and a column`() {
        MATRIX.keys shouldBe ApplicationStatus.entries.toSet()
        MATRIX.values.forEach { it.length shouldBe ApplicationStatus.entries.size }
    }

    @Test
    fun `the pipeline is open, the rest is terminal, and only declined and rejected take a reason`() {
        ApplicationStatus.entries.filter { it.isTerminal } shouldBe
            listOf(ACCEPTED, REJECTED, WITHDRAWN, DECLINED, GHOSTED)
        ApplicationStatus.entries.filter { it.takesDeclineReason } shouldBe listOf(REJECTED, DECLINED)
        ApplicationStatus.INITIAL shouldBe DISCOVERED
    }

    @Test
    fun `every terminal status can be reopened`() {
        ApplicationStatus.entries.filter { it.isTerminal }.forEach { terminal ->
            ApplicationStatus.entries.any { !it.isTerminal && terminal.canMoveTo(it) } shouldBe true
        }
    }

    companion object {
        // Rows: from; columns: to, in declaration order. `x` = allowed. Written out by hand, so a change to
        // the matrix in the code has to be made here (and in ADR-0044) as well.
        //                 DIS SHO PRE APP INT OFF ACC REJ WIT DEC GHO
        private val MATRIX =
            mapOf(
                DISCOVERED to ".xxxxx...x.",
                SHORTLISTED to "x.xxxx...x.",
                PREPARING to "xx.xxx...x.",
                APPLIED to "xxx.xx.xx.x",
                INTERVIEWING to "xxxx.x.xx.x",
                OFFER to "xxxxx.xx.x.",
                ACCEPTED to ".....x.....",
                REJECTED to "...xxx.x...",
                WITHDRAWN to "...xx......",
                DECLINED to "xxx..x...x.",
                GHOSTED to "...xxx.xx..",
            )

        @JvmStatic
        fun everyPair(): List<Arguments> =
            ApplicationStatus.entries.flatMap { from ->
                ApplicationStatus.entries.map { to ->
                    Arguments.of(from, to, MATRIX.getValue(from)[to.ordinal] == 'x')
                }
            }
    }
}
