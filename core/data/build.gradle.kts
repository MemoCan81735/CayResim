plugins {
    id("cayresim.android.library")
    id("cayresim.android.hilt")
}
android {
    namespace = "app.cayresim.core.data"
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:entity"))
    implementation(project(":core:pure"))
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(testFixtures(project(":core:boundary")))
    androidTestImplementation(testFixtures(project(":core:boundary")))
    androidTestImplementation(libs.room.testing)
}
