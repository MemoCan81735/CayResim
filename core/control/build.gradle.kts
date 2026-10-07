plugins { id("cayresim.jvm") }
dependencies {
    api(project(":core:boundary"))
    implementation(project(":core:pure"))
    testImplementation(testFixtures(project(":core:boundary")))
}
kover { reports { verify { rule { minBound(90) } } } }
