plugins {
    id("cayresim.jvm")
    `java-test-fixtures`
}
dependencies {
    implementation(project(":core:pure"))
    api(libs.coroutines.core)
    api(libs.javax.inject)
    testFixturesApi(libs.coroutines.core)
}
