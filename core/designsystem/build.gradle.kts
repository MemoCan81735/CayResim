plugins { id("cayresim.android.compose") }
android { namespace = "app.cayresim.core.designsystem" }
dependencies {
    implementation(project(":core:pure"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
}
