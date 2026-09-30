// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.spi

import org.springframework.modulith.NamedInterface
import org.springframework.modulith.PackageInfo

/**
 * Spring Modulith metadata (Kotlin's `package-info.java`): `applications.application.port.spi` is the
 * named interface `spi` of the applications context, the ports it asks other contexts to implement
 * (`LinkedTasksPort`, ADR-0041). It lives in `bootstrap` so `application` stays free of Spring;
 * `LayerDependencyTest` allows exactly this Modulith annotation here.
 */
@PackageInfo
@NamedInterface("spi")
class ModuleMetadata
