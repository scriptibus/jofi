// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// Refresh lockfiles after a plugin bump: ./gradlew -p build-logic resolveAndLockAll --write-locks
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "$path must be run from the command line with the `--write-locks` flag"
        }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

/** Turns a catalog plugin alias into the Maven coordinates of its plugin marker artifact. */
fun Provider<PluginDependency>.asMarker(): Provider<String> =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}" }

// Plugins the convention plugins apply; their versions come from gradle/libs.versions.toml.
val conventionPlugins =
    with(libs.plugins) {
        listOf(kotlin.jvm, kotlin.spring, spring.boot, detekt, spotless, kover, licensee)
    }

dependencies {
    conventionPlugins.forEach { implementation(it.asMarker()) }
}
