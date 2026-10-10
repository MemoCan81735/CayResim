package app.cayresim.core.boundary

import kotlinx.coroutines.flow.StateFlow

/**
 * Schalter fuer Mess- und Debug-Funktionen (S-011). Nur im Speicher: nach jedem Neustart wieder aus, damit keine
 * Einstellung gespeichert werden muss.
 */
interface DebugOptionsBoundary {
    /** Nachtaufnahmen legen zusaetzlich ihre Einzelbilder als ZIP ab ([SeriesArchiveBoundary]). */
    val saveNightSeries: StateFlow<Boolean>
    fun setSaveNightSeries(on: Boolean)
}

/** Fertig gespeichertes Archiv: Dateiname und Groesse in Byte. */
data class SeriesArchiveSnapshot(val name: String, val bytes: Long)

/** Ein geoeffnetes Archiv; Eintraege werden der Reihe nach geschrieben. */
interface SeriesArchiveSessionBoundary {
    /** false bei Schreibfehler; danach sind weitere Eintraege sinnlos. */
    suspend fun put(name: String, bytes: ByteArray): Boolean
    /** Schliesst und gibt die Datei frei; null bei Fehler (die halbe Datei wird dann geloescht). */
    suspend fun finish(): SeriesArchiveSnapshot?
    /** Bricht ab und loescht die halbe Datei; mehrfach aufrufbar. */
    suspend fun abort()
}

/** Ablage fuer Mess-Archive in Download/CayResim (S-011, V3). */
interface SeriesArchiveBoundary {
    /** Neue Datei `<prefix>-<Datum-Uhrzeit>.zip`; null bei Fehler. */
    suspend fun open(prefix: String): SeriesArchiveSessionBoundary?
}
