package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.DebugOptionsBoundary
import app.cayresim.core.boundary.SeriesArchiveBoundary
import app.cayresim.core.boundary.SeriesArchiveSessionBoundary
import app.cayresim.core.boundary.SeriesArchiveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Archiv im Speicher (S-011). [failOpen], [failPutAfter] und [failFinish] lassen das Speichern scheitern. */
class FakeSeriesArchiveBoundary : SeriesArchiveBoundary {
    var failOpen = false
    /** Ab diesem Eintrag (0-basiert) scheitert put; null = nie. */
    var failPutAfter: Int? = null
    var failFinish = false
    val sessions = mutableListOf<Session>()

    inner class Session(val name: String) : SeriesArchiveSessionBoundary {
        val entries = LinkedHashMap<String, ByteArray>()
        var finished = false; private set
        var aborted = false; private set

        override suspend fun put(name: String, bytes: ByteArray): Boolean {
            check(!finished && !aborted) { "Archiv ist zu" }
            if (failPutAfter?.let { entries.size >= it } == true) return false
            entries[name] = bytes.copyOf(); return true
        }

        override suspend fun finish(): SeriesArchiveSnapshot? {
            if (aborted) return null
            if (failFinish) { aborted = true; return null }
            finished = true
            return SeriesArchiveSnapshot(name, entries.values.sumOf { it.size.toLong() })
        }

        override suspend fun abort() { if (!finished) aborted = true }
    }

    override suspend fun open(prefix: String): SeriesArchiveSessionBoundary? =
        if (failOpen) null else Session("$prefix-20261010-183000.zip").also { sessions += it }
}

class FakeDebugOptionsBoundary(saveNightSeries: Boolean = false) : DebugOptionsBoundary {
    private val state = MutableStateFlow(saveNightSeries)
    override val saveNightSeries: StateFlow<Boolean> = state.asStateFlow()
    override fun setSaveNightSeries(on: Boolean) { state.value = on }
}
