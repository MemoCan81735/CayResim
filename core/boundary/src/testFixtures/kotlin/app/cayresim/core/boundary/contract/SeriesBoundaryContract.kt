package app.cayresim.core.boundary.contract

import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesFailure
import app.cayresim.core.boundary.SeriesResult
import kotlinx.coroutines.flow.first

/** Vertrag fuer Serien: laeuft gegen den Fake (JVM) und gegen Room (Robolectric und Emulator). */
object SeriesBoundaryContract {
    private fun check(c: Boolean, m: String) { if (!c) throw AssertionError(m) }

    suspend fun createAndList(b: SeriesBoundary) {
        val r = b.create("  Garten ")
        check(r is SeriesResult.Ok && r.series.name == "Garten", "Name wird getrimmt gespeichert: $r")
        check(b.series().first().any { it.name == "Garten" }, "Neue Serie erscheint in der Liste")
    }

    suspend fun invalidNames(b: SeriesBoundary) {
        check(b.create("") == SeriesResult.Failed(SeriesFailure.INVALID_NAME), "Leerer Name abgelehnt")
        check(b.create("x".repeat(41)) == SeriesResult.Failed(SeriesFailure.INVALID_NAME), "Zu langer Name abgelehnt")
    }

    suspend fun photosInOrderAndLatest(b: SeriesBoundary) {
        val id = (b.create("S") as SeriesResult.Ok).series.id
        b.addPhoto(id, "u2", 20); b.addPhoto(id, "u1", 10)
        val s = (b.addPhoto(id, "u3", 30) as SeriesResult.Ok).series
        check(s.photoUris == listOf("u1", "u2", "u3"), "Chronologisch: ${s.photoUris}")
        check(s.latestUri == "u3", "Juengstes Foto ist das Overlay")
    }

    suspend fun duplicateAndMissing(b: SeriesBoundary) {
        val id = (b.create("S") as SeriesResult.Ok).series.id
        b.addPhoto(id, "u", 1)
        check(b.addPhoto(id, "u", 2) == SeriesResult.Failed(SeriesFailure.DUPLICATE_PHOTO), "Doppeltes Foto abgelehnt")
        check(b.addPhoto(999_999, "x", 1) == SeriesResult.Failed(SeriesFailure.NOT_FOUND), "Unbekannte Serie")
    }

    suspend fun deleteRemoves(b: SeriesBoundary) {
        val id = (b.create("Weg") as SeriesResult.Ok).series.id
        check(b.delete(id), "Loeschen klappt")
        check(!b.delete(id), "Zweites Loeschen liefert false")
        check(b.series().first().none { it.id == id }, "Geloeschte Serie ist weg")
    }

    val all: List<Pair<String, suspend (SeriesBoundary) -> Unit>> = listOf(
        "Anlegen" to ::createAndList, "Ungueltige Namen" to ::invalidNames, "Reihenfolge" to ::photosInOrderAndLatest,
        "Doppelt und fehlend" to ::duplicateAndMissing, "Loeschen" to ::deleteRemoves,
    )
}
