plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android { namespace = "app.cayresim.core.processing" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:entity"))
    implementation(project(":core:pure"))
    implementation(libs.coroutines.android)
    implementation(libs.exifinterface)
    implementation(libs.core.ktx)
}
