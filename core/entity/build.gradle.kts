plugins { id("cayresim.jvm") }
dependencies { implementation(project(":core:pure")) }
kover { reports { verify { rule { minBound(90) } } } }
