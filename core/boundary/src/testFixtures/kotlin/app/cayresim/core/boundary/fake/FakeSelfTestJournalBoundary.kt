package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.JournalSnapshot
import app.cayresim.core.boundary.SelfTestJournalBoundary

/** Haelt das Protokoll im Speicher; [crashAtStep] simuliert einen Geraete-Neustart vor diesem Schritt. */
class FakeSelfTestJournalBoundary : SelfTestJournalBoundary {
    private var running = false
    private var last: String? = null
    private val photos = mutableListOf<String>()
    val steps = mutableListOf<String>()
    var crashAtStep: String? = null

    override suspend fun unfinished(): JournalSnapshot? = if (running) JournalSnapshot(last, photos.toList()) else null
    override suspend fun begin() { running = true; last = null; photos.clear() }
    override suspend fun step(name: String) {
        last = name.replace('\n', ' '); steps += name
        if (name == crashAtStep) throw SimulatedReboot(name)
    }
    override suspend fun photo(uri: String) { photos += uri }
    override suspend fun finish() { running = false }

    class SimulatedReboot(step: String) : RuntimeException("Neustart bei $step")
}
