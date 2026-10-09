package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.boundary.NightPathSnapshot

class FakeNightPathBoundary(var stored: NightPathSnapshot = NightPathSnapshot()) : NightPathBoundary {
    val saves = mutableListOf<NightPathSnapshot>()
    override suspend fun load(): NightPathSnapshot = stored
    override suspend fun save(snapshot: NightPathSnapshot) { stored = snapshot; saves += snapshot }
}
