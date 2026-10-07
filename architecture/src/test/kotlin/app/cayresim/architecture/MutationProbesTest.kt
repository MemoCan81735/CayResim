package app.cayresim.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruefung des Pruefers: jede Regel muss ihren absichtlichen Verstoss erkennen
 * und darf saubere Gegenbeispiele nicht melden.
 */
class MutationProbesTest {
    private fun rulesFor(path: String, code: String) = ArchitectureRules.checkFile(SourceFile(path, code)).map { it.rule }

    private val ui = "feature/camera/src/main/kotlin/app/cayresim/feature/camera/ui/X.kt"
    private val ctl = "feature/camera/src/main/kotlin/app/cayresim/feature/camera/control/X.kt"
    private val entity = "core/entity/src/main/kotlin/app/cayresim/core/entity/X.kt"
    private val boundary = "core/boundary/src/main/kotlin/app/cayresim/core/boundary/X.kt"
    private val core = "core/control/src/main/kotlin/app/cayresim/core/control/X.kt"
    private val adapter = "core/data/src/main/kotlin/app/cayresim/core/data/X.kt"

    private fun probe(rule: String, path: String, code: String) =
        assertTrue(rule in rulesFor(path, code), "Probe fuer $rule wurde nicht erkannt:\n$code")

    @Test fun `R1 UI importiert Boundary`() = probe("R1", ui, "import app.cayresim.core.boundary.CameraBoundary")
    @Test fun `R1 Control importiert Entity`() = probe("R1", ctl, "import app.cayresim.core.entity.ModeSelectionEntity")
    @Test fun `R1 Entity importiert Boundary`() = probe("R1", entity, "import app.cayresim.core.boundary.PhotoMode")
    @Test fun `R1 Boundary importiert Entity`() = probe("R1", boundary, "import app.cayresim.core.entity.ModeKey")
    @Test fun `R1 Feature kennt fremdes Feature`() = probe("R1", ui, "import app.cayresim.feature.gallery.ui.GalleryRoute")
    @Test fun `R1 Control kennt Adapter`() = probe("R1", core, "import app.cayresim.core.camera.CameraXCameraAdapter")
    @Test fun `R2 Android in Entity`() = probe("R2", entity, "import android.util.Log")
    @Test fun `R2 Uhr in Control`() = probe("R2", core, "fun f() = System.currentTimeMillis()")
    @Test fun `R2 Thread in Boundary`() = probe("R2", boundary, "fun f() { Thread.sleep(5) }")
    @Test fun `R3 GlobalScope`() = probe("R3", ctl, "fun f() = GlobalScope.launch { }")
    @Test fun `R3 object mit var`() = probe("R3", adapter, "object Cache {\n    var last: String? = null\n}")
    @Test fun `R3 Service Locator`() = probe("R3", ui, "val x = EntryPoints.get(app, Foo::class.java)")
    @Test fun `R4 Zufall in Entity`() = probe("R4", entity, "fun f() = kotlin.random.Random.nextInt()")
    @Test fun `R6 Reflexion`() = probe("R6", adapter, "fun f() = Class.forName(\"x\")")
    @Test fun `R7 MediaStore in UI`() = probe("R7", ui, "import android.provider.MediaStore")
    @Test fun `R7 contentResolver in UI`() = probe("R7", ui, "fun f(c: Context) = c.contentResolver")
    @Test fun `R9 oeffentlicher MutableStateFlow`() = probe("R9", ctl, "class AViewModel : ViewModel() {\n    val state = MutableStateFlow(0)\n}")
    @Test fun `R11 CameraX in Feature-Control`() = probe("R11", ctl, "import androidx.camera.core.ImageCapture")
    @Test fun `R11 CameraX in Galerie-UI`() = probe("R11", "feature/gallery/src/main/kotlin/app/cayresim/feature/gallery/ui/X.kt", "import androidx.camera.core.SurfaceRequest")
    @Test fun `R11 Camera2 in Daten-Adapter`() = probe("R11", adapter, "import android.hardware.camera2.CaptureRequest")
    @Test fun `R16 Dispatchers im ViewModel`() = probe("R16", ctl, "fun f() = withContext(Dispatchers.IO) { }")
    @Test fun `Namen Entity-Klasse`() = probe("Namen", entity, "class Rules { }")
    @Test fun `Namen Boundary-Schnittstelle`() = probe("Namen", boundary, "interface Camera { }")
    @Test fun `Namen Adapter`() = probe("Namen", adapter, "class Store @Inject constructor() : MediaBoundary {")
    @Test fun `Namen ViewModel im ui-Paket`() = probe("Namen", ui, "class CameraViewModel : ViewModel() {")

    // Gegenbeispiele: saubere Faelle duerfen nicht gemeldet werden
    @Test fun `A1 Sucher darf CameraXViewfinder nutzen`() =
        assertEquals(emptyList(), rulesFor(ui, "import androidx.camera.compose.CameraXViewfinder\nimport androidx.camera.core.SurfaceRequest"))
    @Test fun `Privater MutableStateFlow ist erlaubt`() =
        assertEquals(emptyList(), rulesFor(ctl, "class AViewModel : ViewModel() {\n    private val state = MutableStateFlow(0)\n}"))
    @Test fun `Kommentare loesen nichts aus`() =
        assertEquals(emptyList(), rulesFor(core, "// System.currentTimeMillis GlobalScope\n/* Thread( */ class XUseCase"))
    @Test fun `object ohne Zustand ist erlaubt`() =
        assertEquals(emptyList(), rulesFor(adapter, "internal object Processing"))
    @Test fun `Testquellen werden nicht geprueft`() =
        assertEquals(emptyList(), rulesFor("core/entity/src/test/kotlin/X.kt", "import android.util.Log\nGlobalScope"))
    @Test fun `DI-Modul darf Dispatchers nutzen`() =
        assertEquals(emptyList(), rulesFor("app/src/main/kotlin/app/cayresim/shell/di/AppModule.kt", "fun f() = Dispatchers.IO"))
}

class NamingFalsePositiveTest {
    @Test fun `Datenklasse vor dem ViewModel wird nicht als ViewModel gezaehlt`() {
        val code = "data class PhotoItem(val uri: String)\n\nclass GalleryViewModel @Inject constructor(m: MediaBoundary) : ViewModel() {\n}"
        assertEquals(emptyList(), ArchitectureRules.checkFile(SourceFile("feature/gallery/src/main/kotlin/app/cayresim/feature/gallery/control/G.kt", code)))
    }
    @Test fun `Falsch benanntes ViewModel nach Datenklasse wird erkannt`() {
        val code = "data class PhotoItem(val uri: String)\n\nclass Gallery @Inject constructor() : ViewModel() {\n}"
        assertTrue(ArchitectureRules.checkFile(SourceFile("feature/gallery/src/main/kotlin/app/cayresim/feature/gallery/control/G.kt", code)).any { it.rule == "Namen" })
    }
}
