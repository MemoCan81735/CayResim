plugins { id("cayresim.jvm") }
dependencies {
    testImplementation(libs.konsist)
}
// S-005: Konsist liest die Quelldateien aller Module. Ohne diese Eingaben lieferte der Build-Cache ein altes Ergebnis:
// vom 7. Oktober 09:58 bis S-005 lief der Test nur noch FROM-CACHE und uebersah einen Verstoss gegen R11.
tasks.test {
    inputs.files(
        fileTree(rootDir) {
            include("**/src/main/**/*.kt", "**/build.gradle.kts")
            exclude("**/build/**", ".gradle/**", ".git/**", "build-logic/**", "config/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("projectSources")
}
