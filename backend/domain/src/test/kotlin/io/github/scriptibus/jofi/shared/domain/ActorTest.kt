// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ActorTest {
    @Test
    fun `named actors keep their name and compare by kind and name`() {
        Actor.Scanner("bundesagentur").name shouldBe "bundesagentur"
        Actor.ExternalClient("Claude Desktop") shouldBe Actor.ExternalClient("Claude Desktop")
        Actor.System("reminder") shouldNotBe Actor.Scanner("reminder")
    }

    @Test
    fun `user and AI are single actors`() {
        Actor.User shouldBe Actor.User
        Actor.User shouldNotBe Actor.Ai
    }

    @Test
    fun `named actors need a name`() {
        shouldThrow<IllegalArgumentException> { Actor.Scanner(" ") }
        shouldThrow<IllegalArgumentException> { Actor.ExternalClient("") }
        shouldThrow<IllegalArgumentException> { Actor.System("\t") }
    }

    @Test
    fun `every actor kind is covered by an exhaustive when`() {
        val kinds =
            listOf(Actor.User, Actor.Ai, Actor.Scanner("s"), Actor.ExternalClient("c"), Actor.System("j")).map {
                when (it) {
                    Actor.User -> "user"
                    Actor.Ai -> "ai"
                    is Actor.Scanner -> "scanner"
                    is Actor.ExternalClient -> "external client"
                    is Actor.System -> "system"
                }
            }

        kinds shouldBe listOf("user", "ai", "scanner", "external client", "system")
    }
}
