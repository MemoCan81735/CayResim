package app.cayresim.core.boundary

/** Stand eines Selbsttests, der nicht zu Ende kam (z. B. weil das Geraet neu gestartet ist). */
data class JournalSnapshot(val lastStep: String?, val photoUris: List<String>)

/**
 * Fortschrittsprotokoll des Selbsttests. Jeder Eintrag ist sofort dauerhaft gespeichert,
 * damit er einen Neustart des Geraets uebersteht.
 */
interface SelfTestJournalBoundary {
    /** Liefert den abgebrochenen Lauf oder null, wenn der letzte Lauf vollstaendig war. */
    suspend fun unfinished(): JournalSnapshot?
    /** Beginnt einen neuen Lauf und verwirft den alten. */
    suspend fun begin()
    suspend fun step(name: String)
    suspend fun photo(uri: String)
    /** Markiert den Lauf als vollstaendig. */
    suspend fun finish()
}
