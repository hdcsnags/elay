plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt.yml"))
        // KMP source sets aren't picked up by the plain detekt task's default
        // source dirs — without this, :shared:detekt is NO-SOURCE (found by
        // seat B1, 2026-09-11: a silently vacuous lint rail).
        source.setFrom(files("src"))
    }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        filter {
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }
}


// Stage 5 hardening item 5, enforceable form (pre-gate F18: detekt's ForbiddenMethodCall
// is @RequiresTypeResolution and the plain `detekt` task has no classpath, so that rule is
// provably inert -- 167/167 files scanned, two live printlns, zero findings). This task is
// the rail that actually fires: any `println(` in shared/src outside ElayLog's own actuals
// fails the build. Wired into `check`.
val assertNoPrintln by tasks.registering {
    val srcRoots = listOf(file("shared/src"), file("androidApp/src"))
    val allowed = setOf("ElayLog.kt", "ElayLog.android.kt", "ElayLog.ios.kt")
    srcRoots.forEach { inputs.dir(it) }
    doLast {
        val offenders =
            srcRoots.asSequence().flatMap { root -> root.walkTopDown().map { root to it } }
                .map { it.second }
                .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
                .flatMap { f ->
                    f.readLines().mapIndexedNotNull { i, l ->
                        if (l.contains("println(") && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*")) {
                            f.path + ":" + (i + 1) + "  " + l.trim()
                        } else {
                            null
                        }
                    }
                }
                .toList()
        require(offenders.isEmpty()) {
            "println() is forbidden outside dev.elay.util.ElayLog (release builds must emit nothing):" +
                System.lineSeparator() + offenders.joinToString(System.lineSeparator())
        }
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(assertNoPrintln) }
}
