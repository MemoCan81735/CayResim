// S-008, R28: einziges Modul, das die Tonaufnahme von Android kennt.
plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android { namespace = "app.cayresim.core.audio" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:pure"))
    implementation(libs.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(testFixtures(project(":core:boundary")))
}
