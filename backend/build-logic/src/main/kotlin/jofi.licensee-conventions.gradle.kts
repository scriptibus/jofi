// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// License gate (docs/spec/04-tech-stack-proposal.md 4.6a). Jofi is AGPL-3.0-or-later, so every
// dependency must carry a compatible license. Anything not listed here fails `check` until a
// human reviews it. Add `allowUrl`/`allowDependency` entries only for a real dependency, each
// with a `because` explaining which allowlisted license it actually is.

import app.cash.licensee.UnusedAction

plugins {
    id("app.cash.licensee")
}

licensee {
    // The allowlist is a policy, not an inventory: unused entries are expected.
    unusedAction(UnusedAction.IGNORE)

    allow("MIT")
    allow("Apache-2.0")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("ISC")
    allow("MPL-2.0")
    allow("LGPL-2.1-only")
    allow("LGPL-2.1-or-later")
    allow("LGPL-3.0-only")
    allow("LGPL-3.0-or-later")
    allow("EPL-2.0")
    allow("GPL-3.0-only")
    allow("GPL-3.0-or-later")
    allow("AGPL-3.0-only")
    allow("AGPL-3.0-or-later")

    allowUrl("https://opensource.org/license/mit") {
        because("MIT, declared by URL instead of SPDX id (org.slf4j:slf4j-api, jul-to-slf4j via Spring Boot logging)")
    }
    allowUrl("https://github.com/flyway/flyway/blob/main/README.txt") {
        because("Apache-2.0, declared by name + URL in flyway-parent (org.flywaydb:flyway-*)")
    }
    allowUrl("https://www.jooq.org/inc/LICENSE.txt") {
        because("Apache-2.0 (jOOQ Open Source Edition), declared by name + URL in jooq-parent (org.jooq:jooq*)")
    }
    allowUrl("https://jdbc.postgresql.org/about/license.html") {
        because("BSD-2-Clause, named in the pom of org.postgresql:postgresql")
    }
    allowUrl("https://www.bouncycastle.org/licence.html") {
        because("MIT: the Bouncy Castle Licence is the MIT license text (org.bouncycastle:bcprov-jdk18on, argon2id)")
    }
    // Spring Session 4.1.1's poms name a "Broadcom Foundation License" by a release-tooling bug
    // (https://github.com/spring-projects/spring-session/issues/3910); the jars ship Apache-2.0
    // LICENSE.txt and the repository is Apache-2.0. Pinned to 4.1.1 so the next version is checked again.
    listOf("spring-session-core", "spring-session-jdbc").forEach { artifact ->
        allowDependency("org.springframework.session", artifact, "4.1.1") {
            because(
                "Apache-2.0 (LICENSE.txt in the jar); the pom's license name is a known release bug, spring-session#3910",
            )
        }
    }
    // Exact artifacts, not the URL: a future ANTLR release must be checked again.
    listOf("antlr4-runtime" to "4.13.1", "ST4" to "4.3.4", "antlr-runtime" to "3.5.3").forEach { (name, version) ->
        allowDependency("org.antlr", name, version) {
            because("BSD-3-Clause, declared only by URL (antlr.org/license.html); via Spring AI's prompt templates")
        }
    }
    allowDependency("org.reactivestreams", "reactive-streams", "1.0.4") {
        because("MIT-0 (MIT without attribution; already accepted for the frontend), via jOOQ -> r2dbc-spi")
    }
}
