plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android { namespace = "app.cayresim.core.camera" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:entity"))
    implementation(project(":core:pure"))
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.extensions)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.core.ktx)
    androidTestImplementation(testFixtures(project(":core:boundary")))
    androidTestImplementation(libs.exifinterface)
}
