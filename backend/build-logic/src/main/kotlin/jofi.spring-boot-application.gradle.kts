// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// The runnable Spring Boot application (only the bootstrap module applies this).

plugins {
    id("jofi.spring-conventions")
    id("org.springframework.boot")
}

springBoot {
    // Generates META-INF/build-info.properties (exposed as BuildProperties). The build time is
    // left out so the output stays reproducible and cacheable.
    buildInfo {
        excludes.set(setOf("time"))
    }
}
