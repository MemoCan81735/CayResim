// Reines Kotlin ohne Android (R2): pure, entity, boundary, control, architecture.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
kotlin { jvmToolchain(17) }
dependencies {
    "testImplementation"(libs.findLibrary("kotlin-test").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
    "testImplementation"(libs.findLibrary("turbine").get())
}
tasks.withType<Test>().configureEach { maxHeapSize = "2g"; failOnNoDiscoveredTests = false }
