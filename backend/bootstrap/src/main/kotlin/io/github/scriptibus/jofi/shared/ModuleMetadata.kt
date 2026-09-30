// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Spring Modulith metadata for the `shared` kernel (Kotlin's replacement for `package-info.java`).
 * The kernel is an open module: every context may use its domain types and ports (`Actor`,
 * `ChangelogPort`, the AI/HTTP/job/secret ports). It lives in `bootstrap` so `domain` and
 * `application` stay free of Spring; the ArchUnit layer rules still keep other contexts away from
 * the kernel's adapters (ADR-0032).
 */
@PackageInfo
@ApplicationModule(type = ApplicationModule.Type.OPEN)
class ModuleMetadata
