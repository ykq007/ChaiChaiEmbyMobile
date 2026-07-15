plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
}

allprojects { dependencyLocking { lockAllConfigurations() } }

tasks.register("verifyModuleGraph") {
    group = "verification"
    description = "Fails when one feature module depends directly on another."
    inputs.files(
        fileTree("feature") { include("*/build.gradle*") },
        file("gradle/feature-module.gradle"),
    )
    doLast {
        val forbidden = Regex("""project\s*\(\s*["']:feature:""")
        val violations = inputs.files.filter { forbidden.containsMatchIn(it.readText()) }
        check(violations.isEmpty()) {
            "Feature modules must not depend directly on one another: ${violations.joinToString()}"
        }
    }
}
