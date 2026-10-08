package app.cayresim.core.data

import app.cayresim.core.boundary.JournalSnapshot
import app.cayresim.core.boundary.contract.SelfTestJournalBoundaryContract
import app.cayresim.core.boundary.fake.FakeSelfTestJournalBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSelfTestJournalAdapterTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun adapter(file: File = File(tmp.newFolder(), "j.txt")) = FileSelfTestJournalAdapter(file, Dispatchers.Unconfined)

    @Test fun `Datei-Adapter erfuellt den Protokoll-Vertrag`() = runTest {
        SelfTestJournalBoundaryContract.all.forEach { (_, case) -> case(adapter()) }
    }

    @Test fun `Fake erfuellt denselben Vertrag`() = runTest {
        SelfTestJournalBoundaryContract.all.forEach { (_, case) -> case(FakeSelfTestJournalBoundary()) }
    }

    @Test fun `Stand uebersteht einen Neustart, neue Instanz liest ihn`() = runTest {
        val file = File(tmp.newFolder(), "j.txt")
        adapter(file).apply { begin(); step("Aufnahme NIGHT"); photo("content://x/1") }
        assertEquals(JournalSnapshot("Aufnahme NIGHT", listOf("content://x/1")), adapter(file).unfinished())
    }

    @Test fun `R18 Erzeugen greift nicht auf den Speicher zu`() = runTest {
        // Fehler aus dem Emulatorlauf: filesDir im Konstruktor, Hilt baut ihn auf dem Main-Thread (StrictMode)
        var resolved = 0
        val f = File(tmp.newFolder(), "j.txt")
        val a = FileSelfTestJournalAdapter({ resolved++; f }, Dispatchers.Unconfined)
        assertEquals(0, resolved, "Datei schon beim Erzeugen aufgeloest")
        a.begin(); a.step("x"); assertEquals(1, resolved)
    }

    @Test fun `Fehlerfall kaputte Datei fuehrt nicht zum Absturz`() = runTest {
        val file = File(tmp.newFolder(), "j.txt").apply { writeBytes(byteArrayOf(0, -1, 10, 65)) }
        adapter(file).unfinished() // darf nicht werfen
    }

    @Test fun `Fehlerfall nicht beschreibbarer Ort wirft nicht`() = runTest {
        val dir = tmp.newFolder()
        val a = adapter(File(dir, "fehlt/j.txt"))
        a.begin(); a.step("A"); a.finish()
        assertNull(a.unfinished())
    }
}
