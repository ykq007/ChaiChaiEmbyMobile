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
    ":platform:server",
    ":feature:home",
    ":feature:libraries",
    ":feature:search",
    ":feature:settings",
    ":feature:server-setup",
)
