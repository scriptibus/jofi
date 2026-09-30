// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port.api

import org.springframework.modulith.NamedInterface
import org.springframework.modulith.PackageInfo

/**
 * Spring Modulith metadata (Kotlin's `package-info.java`): `setup.application.port.api` is the named interface `api`
 * of the setup context, the ports other contexts may call (`CheckAiTaskAssignedPort` for the posting import, #96).
 * Only plain values cross it. It lives in `bootstrap` so `application` stays free of Spring; `LayerDependencyTest`
 * allows exactly this Modulith annotation here.
 */
@PackageInfo
@NamedInterface("api")
class ModuleMetadata
