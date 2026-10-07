plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android { namespace = "app.cayresim.core.data" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:entity"))
    implementation(project(":core:pure"))
    implementation(libs.coroutines.android)
}
dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
