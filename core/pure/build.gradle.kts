plugins { id("cayresim.jvm") }
kover { reports { verify { rule { minBound(90) } } } }
// S-005: Der Laborbericht entsteht im Test. Als Ausgabe angemeldet, stellt der Build-Cache ihn mit wieder her;
// vorher fehlte er in jedem Lauf, in dem die Tests aus dem Cache kamen.
tasks.test {
    outputs.file(layout.buildDirectory.file("quality-report.md")).withPropertyName("qualityReport")
    outputs.dir(layout.buildDirectory.dir("quality-report-parts")).withPropertyName("qualityReportParts")
}
