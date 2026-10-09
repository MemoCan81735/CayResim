plugins {
    id("cayresim.jvm")
    `java-test-fixtures`
}
dependencies {
    // Schnittstellen nutzen reine Typen (z. B. NightPath), Verbraucher brauchen sie auf dem Klassenpfad
    api(project(":core:pure"))
    api(libs.coroutines.core)
    api(libs.javax.inject)
    testFixturesApi(libs.coroutines.core)
    testFixturesApi(project(":core:pure"))
}
