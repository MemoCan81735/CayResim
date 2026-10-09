package app.cayresim.core.boundary.contract

import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.boundary.NightPathSnapshot
import app.cayresim.core.pure.NightPath
import app.cayresim.core.pure.NightPathRule

/** Gemeinsamer Vertrag fuer Fake und Datei-Adapter. */
object NightPathBoundaryContract {
    private fun check(ok: Boolean, msg: String) { if (!ok) throw AssertionError(msg) }

    val all: List<Pair<String, suspend (NightPathBoundary) -> Unit>> = listOf(
        "ohne Pruefung 8 Bit" to { b -> check(b.load().path == NightPath.YUV, "Standard muss 8 Bit sein") },
        "Wahl wird gespeichert" to { b ->
            val s = NightPathSnapshot(NightPath.RAW, NightPathRule.Reason.RAW_OK)
            b.save(s); check(b.load() == s, "gelesen ${b.load()} statt $s")
            val y = NightPathSnapshot(NightPath.YUV, NightPathRule.Reason.SLOW_STREAM)
            b.save(y); check(b.load() == y, "Ueberschreiben gescheitert")
        },
    )
}
