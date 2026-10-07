package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesFailure
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.SeriesSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake mit denselben Regeln wie der Room-Adapter (siehe SeriesBoundaryContract). */
class FakeSeriesBoundary : SeriesBoundary {
    private data class Row(val id: Long, val name: String, val photos: List<Pair<String, Long>>)
    private val rows = MutableStateFlow<List<Row>>(emptyList())
    private val out = MutableStateFlow<List<SeriesSnapshot>>(emptyList())
    private var nextId = 1L
    var failStorage = false

    override fun series(): Flow<List<SeriesSnapshot>> = out

    private fun publish() { out.value = rows.value.map { r -> SeriesSnapshot(r.id, r.name, r.photos.sortedBy { it.second }.map { it.first }) } }

    override suspend fun create(name: String): SeriesResult {
        if (failStorage) return SeriesResult.Failed(SeriesFailure.STORAGE)
        val n = name.trim()
        if (n.isEmpty() || n.length > 40) return SeriesResult.Failed(SeriesFailure.INVALID_NAME)
        val r = Row(nextId++, n, emptyList()); rows.value = rows.value + r; publish()
        return SeriesResult.Ok(out.value.first { it.id == r.id })
    }

    override suspend fun addPhoto(seriesId: Long, uri: String, takenAtMillis: Long): SeriesResult {
        if (failStorage) return SeriesResult.Failed(SeriesFailure.STORAGE)
        val r = rows.value.firstOrNull { it.id == seriesId } ?: return SeriesResult.Failed(SeriesFailure.NOT_FOUND)
        if (r.photos.any { it.first == uri }) return SeriesResult.Failed(SeriesFailure.DUPLICATE_PHOTO)
        if (r.photos.size >= 2_000) return SeriesResult.Failed(SeriesFailure.FULL)
        rows.value = rows.value.map { if (it.id == seriesId) it.copy(photos = it.photos + (uri to takenAtMillis)) else it }
        publish()
        return SeriesResult.Ok(out.value.first { it.id == seriesId })
    }

    override suspend fun delete(seriesId: Long): Boolean {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id == seriesId }; publish()
        return rows.value.size < before
    }
}
