plugins {
    id("cayresim.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.cayresim.core.designsystem"
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core:pure"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
}
