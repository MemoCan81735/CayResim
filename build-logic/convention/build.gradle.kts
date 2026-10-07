plugins {
    `kotlin-dsl`
}
dependencies {
    implementation(libs.android.gradle)
    implementation(libs.kotlin.gradle)
    implementation(libs.compose.gradle)
    implementation(libs.serialization.gradle)
    implementation(libs.ksp.gradle)
    implementation(libs.hilt.gradle)
    implementation(libs.kover.gradle)
    implementation(libs.roborazzi.gradle)
}
