pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ChaiChaiEmbyMobile"

include(
    ":app",
    ":core:contracts",
    ":design:system",
    ":platform:adaptive",
    ":platform:proxy",
    ":platform:server",
    ":platform:playback",
    ":platform:danmaku",
    ":platform:subtitles",
    ":feature:playback",
    ":feature:home",
    ":feature:libraries",
    ":feature:search",
    ":feature:settings",
    ":feature:server-setup",
)
