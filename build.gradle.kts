plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
}

val secureNettyVersion = "4.1.135.Final"
val secureBouncyCastleVersion = "1.84"

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            // AGP 9.3.0 is the latest stable API-37 toolchain, but its UTP and lint tools still
            // declare vulnerable patch levels. Keep each library family aligned while AGP catches up.
            force(
                "io.netty:netty-buffer:$secureNettyVersion",
                "io.netty:netty-codec:$secureNettyVersion",
                "io.netty:netty-codec-http:$secureNettyVersion",
                "io.netty:netty-codec-http2:$secureNettyVersion",
                "io.netty:netty-codec-socks:$secureNettyVersion",
                "io.netty:netty-common:$secureNettyVersion",
                "io.netty:netty-handler:$secureNettyVersion",
                "io.netty:netty-handler-proxy:$secureNettyVersion",
                "io.netty:netty-resolver:$secureNettyVersion",
                "io.netty:netty-transport:$secureNettyVersion",
                "io.netty:netty-transport-native-unix-common:$secureNettyVersion",
                "org.bouncycastle:bcpkix-jdk18on:$secureBouncyCastleVersion",
                "org.bouncycastle:bcprov-jdk18on:$secureBouncyCastleVersion",
                "org.bouncycastle:bcutil-jdk18on:$secureBouncyCastleVersion",
            )
        }
    }
    dependencyLocking { lockAllConfigurations() }
}

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
