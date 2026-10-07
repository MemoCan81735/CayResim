plugins { id("cayresim.jvm") }
kover { reports { verify { rule { minBound(90) } } } }
