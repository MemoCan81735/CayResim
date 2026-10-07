package app.cayresim.core.boundary.contract

import app.cayresim.core.boundary.JournalSnapshot
import app.cayresim.core.boundary.SelfTestJournalBoundary

/** Gemeinsamer Vertrag fuer Fake und Datei-Adapter (Testebene 3). Jeder Fall bekommt ein frisches Protokoll. */
object SelfTestJournalBoundaryContract {
    private fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("erwartet $expected, war $actual") }
    private fun assertNull(actual: Any?) = assertEquals(null, actual)
    val all: List<Pair<String, suspend (SelfTestJournalBoundary) -> Unit>> = listOf(
        "leeres Protokoll meldet keinen Abbruch" to { j -> assertNull(j.unfinished()) },
        "offener Lauf meldet letzten Schritt und Fotos" to { j ->
            j.begin(); j.step("A"); j.photo("u1"); j.step("B"); j.photo("u2")
            assertEquals(JournalSnapshot("B", listOf("u1", "u2")), j.unfinished())
        },
        "abgeschlossener Lauf meldet keinen Abbruch" to { j ->
            j.begin(); j.step("A"); j.finish()
            assertNull(j.unfinished())
        },
        "neuer Lauf verwirft den alten" to { j ->
            j.begin(); j.step("A"); j.photo("u1")
            j.begin()
            assertEquals(JournalSnapshot(null, emptyList()), j.unfinished())
        },
        "Randfall Schrittname mit Zeilenumbruch bleibt ein Schritt" to { j ->
            j.begin(); j.step("A\nB")
            assertEquals(1, j.unfinished()!!.lastStep!!.lines().size)
        },
    )
}
