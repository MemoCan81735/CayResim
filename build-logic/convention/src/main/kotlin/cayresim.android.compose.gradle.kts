// Compose fuer Bibliotheken ohne Feature-Logik (Designsystem).
plugins {
    id("cayresim.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    buildFeatures { compose = true }
}
