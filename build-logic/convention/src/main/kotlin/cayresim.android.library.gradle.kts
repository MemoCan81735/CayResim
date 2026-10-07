// Android-Bibliothek: Adapter (camera, processing, data) und Grundlage fuer Features.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlinx.kover")
}
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    "testImplementation"(libs.findLibrary("kotlin-test").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
    "testImplementation"(libs.findLibrary("turbine").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-rules").get())
    "androidTestImplementation"(libs.findLibrary("androidx-test-ext").get())
    "androidTestImplementation"(libs.findLibrary("coroutines-test").get())
    "androidTestImplementation"(libs.findLibrary("kotlin-test").get())
}
tasks.withType<Test>().configureEach { maxHeapSize = "3g"; failOnNoDiscoveredTests = false }
