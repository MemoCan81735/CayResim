package app.cayresim.core.boundary

import app.cayresim.core.pure.NightPath
import app.cayresim.core.pure.NightPathRule

/** Gespeicherte Wahl des Nachtwegs fuer dieses Geraet; ohne Pruefung immer der 8-Bit-Weg. */
data class NightPathSnapshot(val path: NightPath = NightPath.YUV, val reason: NightPathRule.Reason? = null)

/** Speicher fuer die Wahl aus dem Selbsttest (R26: mit Formatversion, Unbekanntes faellt auf den 8-Bit-Weg). */
interface NightPathBoundary {
    suspend fun load(): NightPathSnapshot
    suspend fun save(snapshot: NightPathSnapshot)
}
