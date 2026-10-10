plugins { id("cayresim.android.feature") }
android { namespace = "app.cayresim.feature.settings" }
dependencies {
    // S-008: Berechtigungsabfrage fuer das Mikrofon
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
}
