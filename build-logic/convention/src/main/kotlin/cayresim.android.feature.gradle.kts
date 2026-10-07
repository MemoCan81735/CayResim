// Feature-Modul: UI (Compose, Paket ui) und Control (ViewModels, Paket control).
plugins {
    id("cayresim.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.github.takahirom.roborazzi")
}
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    buildFeatures { compose = true }
}
dependencies {
    "implementation"(project(":core:boundary"))
    "implementation"(project(":core:control"))
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:pure"))
    "implementation"(platform(libs.findLibrary("compose-bom").get()))
    "implementation"(libs.findLibrary("compose-ui").get())
    "implementation"(libs.findLibrary("compose-material3").get())
    "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
    "implementation"(libs.findLibrary("lifecycle-runtime-compose").get())
    "implementation"(libs.findLibrary("lifecycle-viewmodel-compose").get())
    "implementation"(libs.findLibrary("hilt-android").get())
    "implementation"(libs.findLibrary("hilt-viewmodel-compose").get())
    "ksp"(libs.findLibrary("hilt-compiler").get())
    "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
    "testImplementation"(testFixtures(project(":core:boundary")))
    "testImplementation"(platform(libs.findLibrary("compose-bom").get()))
    "testImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
    "testImplementation"(libs.findLibrary("robolectric").get())
    "testImplementation"(libs.findLibrary("roborazzi").get())
    "testImplementation"(libs.findLibrary("roborazzi-compose").get())
    "testImplementation"(libs.findLibrary("roborazzi-rule").get())
    "testImplementation"(libs.findLibrary("androidx-test-ext").get())
}
