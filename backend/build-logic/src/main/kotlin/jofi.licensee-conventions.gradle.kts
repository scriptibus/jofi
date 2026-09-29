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
}
