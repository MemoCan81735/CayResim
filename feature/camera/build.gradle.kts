plugins { id("cayresim.android.feature") }
android { namespace = "app.cayresim.feature.camera" }
dependencies {
    // Ausnahme A1: nur fuer den Compose-Sucher und das Auspacken der SurfaceRequest.
    implementation(libs.camerax.compose)
    implementation(libs.camerax.core)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
}
