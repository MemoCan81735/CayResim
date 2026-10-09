package app.cayresim.core.data

import app.cayresim.core.boundary.NightPathSnapshot
import app.cayresim.core.boundary.contract.NightPathBoundaryContract
import app.cayresim.core.boundary.fake.FakeNightPathBoundary
import app.cayresim.core.pure.NightPath
import app.cayresim.core.pure.NightPathRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

class FileNightPathAdapterTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun adapter(file: File = File(tmp.newFolder(), "n.txt")) = FileNightPathAdapter(file, Dispatchers.Unconfined)

    @Test fun `Datei-Adapter und Fake erfuellen den Vertrag`() = runTest {
        NightPathBoundaryContract.all.forEach { (_, case) -> case(adapter()); case(FakeNightPathBoundary()) }
    }

    @Test fun `Wahl uebersteht einen Neustart`() = runTest {
        val f = File(tmp.newFolder(), "n.txt")
        adapter(f).save(NightPathSnapshot(NightPath.RAW, NightPathRule.Reason.RAW_OK))
        assertEquals(NightPath.RAW, adapter(f).load().path)
    }

    @Test fun `Fehlerfall kaputte oder fremde Datei ergibt 8 Bit`() = runTest {
        for (content in listOf("", "v9 RAW RAW_OK", "v1 BLAU", "\u0000ÿ")) {
            val f = File(tmp.newFolder(), "n.txt").apply { writeText(content) }
            assertEquals(NightPathSnapshot(), adapter(f).load(), "Inhalt '$content'")
        }
    }

    @Test fun `Randfall unbekannter Grund behaelt den Weg`() = runTest {
        val f = File(tmp.newFolder(), "n.txt").apply { writeText("v1 RAW NEU") }
        assertEquals(NightPathSnapshot(NightPath.RAW, null), adapter(f).load())
    }

    @Test fun `R18 Erzeugen greift nicht auf den Speicher zu`() = runTest {
        var resolved = 0
        val f = File(tmp.newFolder(), "n.txt")
        val a = FileNightPathAdapter({ resolved++; f }, Dispatchers.Unconfined)
        assertEquals(0, resolved); a.load(); assertEquals(1, resolved)
    }
}
