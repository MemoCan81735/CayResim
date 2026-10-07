pluginManagement {
    includeBuild("build-logic")
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
rootProject.name = "CayResim"
include(
    ":app",
    ":feature:camera",
    ":feature:gallery",
    ":feature:settings",
    ":core:designsystem",
    ":core:control",
    ":core:boundary",
    ":core:entity",
    ":core:pure",
    ":core:camera",
    ":core:processing",
    ":core:data",
    ":architecture",
)
