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
    ":core:audio",
    ":architecture",
)
// S-005: Lint-Selbstpruefung. Das Probe-Modul enthaelt absichtlich einen Fund und wird nur im Analyse-Job
// mit -Pprobes eingebunden; es kommt nie in die App und nie in die Architekturpruefung.
if (providers.gradleProperty("probes").isPresent) {
    include(":probe-lint")
    project(":probe-lint").projectDir = file("config/probes/lint")
}
