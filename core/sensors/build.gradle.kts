// S-010, R29: einziges Modul, das die Lagesensoren von Android kennt.
plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android { namespace = "app.cayresim.core.sensors" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:pure"))
    implementation(libs.coroutines.android)
}
