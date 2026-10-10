package app.cayresim.architecture

/** Eine Quelldatei: Pfad relativ zum Projekt und Inhalt. */
data class SourceFile(val path: String, val text: String)

data class Violation(val rule: String, val path: String, val detail: String) {
    override fun toString() = "$path [$rule] $detail"
}

/**
 * Pruefregeln der Architekturvorgaben als reine Funktion ueber Quelltexten.
 * Konsist liefert die Dateien des Projekts; die Mutationsproben fuettern absichtliche Verstoesse hinein.
 * Gilt nur fuer Hauptquellen (src/main), nicht fuer Tests.
 */
object ArchitectureRules {

    enum class Layer { PURE, ENTITY, BOUNDARY, CONTROL_CORE, ADAPTER, FEATURE_CONTROL, FEATURE_UI, DESIGNSYSTEM, SHELL, ARCHITECTURE, UNKNOWN }

    fun layerOf(path: String): Layer = when {
        "/test/" in path || "/androidTest/" in path || "/testFixtures/" in path -> Layer.UNKNOWN
        path.startsWith("core/pure/") -> Layer.PURE
        path.startsWith("core/entity/") -> Layer.ENTITY
        path.startsWith("core/boundary/") -> Layer.BOUNDARY
        path.startsWith("core/control/") -> Layer.CONTROL_CORE
        path.startsWith("core/camera/") || path.startsWith("core/data/") || path.startsWith("core/processing/") || path.startsWith("core/audio/") -> Layer.ADAPTER
        path.startsWith("core/designsystem/") -> Layer.DESIGNSYSTEM
        path.startsWith("feature/") && "/control/" in path -> Layer.FEATURE_CONTROL
        path.startsWith("feature/") && "/ui/" in path -> Layer.FEATURE_UI
        path.startsWith("app/") -> Layer.SHELL
        path.startsWith("architecture/") -> Layer.ARCHITECTURE
        else -> Layer.UNKNOWN
    }

    private val importRegex = Regex("""^import\s+([\w.]+)""", RegexOption.MULTILINE)
    fun imports(text: String): List<String> = importRegex.findAll(text).map { it.groupValues[1] }.toList()

    private const val P = "app.cayresim."

    /** R1: erlaubte Ziele je Schicht (Paketpraefixe der eigenen App). */
    private val allowed: Map<Layer, List<String>> = mapOf(
        Layer.PURE to listOf("${P}core.pure"),
        Layer.ENTITY to listOf("${P}core.pure", "${P}core.entity"),
        Layer.BOUNDARY to listOf("${P}core.pure", "${P}core.boundary"),
        Layer.CONTROL_CORE to listOf("${P}core.pure", "${P}core.boundary", "${P}core.control"),
        Layer.ADAPTER to listOf("${P}core.pure", "${P}core.boundary", "${P}core.entity", "${P}core.camera", "${P}core.data", "${P}core.processing", "${P}core.audio"),
        Layer.DESIGNSYSTEM to listOf("${P}core.pure", "${P}core.designsystem"),
        Layer.FEATURE_CONTROL to listOf("${P}core.pure", "${P}core.boundary", "${P}core.control", "${P}feature"),
        // UI: nur eigene Control, Designsystem, pure und eigene Ressourcen (R1). Feature-Grenzen pruefen wir unten.
        Layer.FEATURE_UI to listOf("${P}core.pure", "${P}core.designsystem", "${P}feature"),
        Layer.SHELL to listOf(P),
        Layer.ARCHITECTURE to listOf("${P}architecture"),
    )

    private val inner = setOf(Layer.PURE, Layer.ENTITY, Layer.BOUNDARY, Layer.CONTROL_CORE)

    fun check(files: List<SourceFile>): List<Violation> = files.flatMap { checkFile(it) }

    fun checkFile(f: SourceFile): List<Violation> {
        val layer = layerOf(f.path)
        if (layer == Layer.UNKNOWN || layer == Layer.ARCHITECTURE) return emptyList()
        val out = mutableListOf<Violation>()
        val imps = imports(f.text)
        val code = stripComments(f.text)

        // R1 Schichtgrenzen
        allowed[layer]?.let { ok ->
            imps.filter { it.startsWith(P) && ok.none { prefix -> it.startsWith(prefix) } }
                .forEach { out += Violation("R1", f.path, "${layer.name} darf $it nicht kennen") }
        }
        if (layer == Layer.FEATURE_UI || layer == Layer.FEATURE_CONTROL) {
            val own = Regex("""^feature/(\w+)/""").find(f.path)?.groupValues?.get(1)
            imps.filter { it.startsWith("${P}feature.") && !it.startsWith("${P}feature.$own.") }
                .forEach { out += Violation("R1", f.path, "Feature $own darf fremdes Feature $it nicht kennen") }
        }
        // R2 innere Schichten: kein Android, keine Uhr, keine Threads
        if (layer in inner) {
            imps.filter { it.startsWith("android.") || it.startsWith("androidx.") }
                .forEach { out += Violation("R2", f.path, "Android-Import in innerer Schicht: $it") }
            listOf("System.currentTimeMillis", "System.nanoTime", "Instant.now", "LocalDateTime.now", "Thread(", "Thread.sleep")
                .filter { it in code }.forEach { out += Violation("R2", f.path, "Uhr oder Thread in innerer Schicht: $it") }
        }
        // R3 kein Globales
        if ("GlobalScope" in code) out += Violation("R3", f.path, "GlobalScope")
        if (Regex("""EntryPoints?\.get""").containsMatchIn(code)) out += Violation("R3", f.path, "Service Locator (EntryPoints)")
        Regex("""(?m)^\s*(?:internal\s+|private\s+)?object\s+\w+[^{]*\{([\s\S]*?)^}""").findAll(code).forEach { m ->
            if (Regex("""(?m)^\s{4}(?:private\s+|internal\s+)?var\s""").containsMatchIn(m.groupValues[1]))
                out += Violation("R3", f.path, "object mit veraenderlichem Zustand")
        }
        // R4 Zufall
        if (layer in inner || layer == Layer.FEATURE_CONTROL) {
            listOf("Math.random", "kotlin.random", "java.util.Random", "Random.next", "Random(")
                .filter { it in code }.forEach { out += Violation("R4", f.path, "Zufall ohne Rng-Schnittstelle: $it") }
        }
        // R6 Reflexion
        listOf("kotlin.reflect.full", "Class.forName", "getDeclaredField", "getDeclaredMethod", "setAccessible(")
            .filter { it in code }.forEach { out += Violation("R6", f.path, "Reflexion: $it") }
        // R7 kein Speicher in der UI
        if (layer == Layer.FEATURE_UI) {
            imps.filter { i -> listOf("android.provider.MediaStore", "androidx.room", "androidx.datastore", "java.io.File", "android.content.ContentResolver", "android.content.SharedPreferences").any { i.startsWith(it) } }
                .forEach { out += Violation("R7", f.path, "Speicherzugriff in der UI: $it") }
            if ("contentResolver" in code) out += Violation("R7", f.path, "contentResolver in der UI")
        }
        // R9 MutableStateFlow privat
        if (layer == Layer.FEATURE_CONTROL || layer == Layer.CONTROL_CORE) {
            Regex("""(?m)^\s*(?!private\b)(?:internal\s+|public\s+)?va[lr]\s+\w+\s*(?::[^=]*)?=\s*MutableStateFlow""").findAll(code)
                .forEach { out += Violation("R9", f.path, "MutableStateFlow muss private sein: ${it.value.trim()}") }
        }
        // R11 CameraX nur im Kamera-Adapter, Ausnahme A1
        if (!f.path.startsWith("core/camera/")) {
            imps.filter { it.startsWith("androidx.camera.") || it.startsWith("android.hardware.camera2") }
                .filterNot { isA1(f.path, it) }
                .forEach { out += Violation("R11", f.path, "CameraX ausserhalb von :core:camera: $it") }
        }
        // R28 Tonaufnahme nur im Mikrofon-Adapter (S-008)
        if (!f.path.startsWith("core/audio/")) {
            imps.filter { i -> AUDIO_CAPTURE.any { i == it || i.startsWith("$it.") } }
                .forEach { out += Violation("R28", f.path, "Tonaufnahme ausserhalb von :core:audio: $it") }
            AUDIO_CAPTURE.filter { it in code }
                .forEach { out += Violation("R28", f.path, "Tonaufnahme ausserhalb von :core:audio: $it") }
        }
        // R16 Dispatchers nur im DI-Modul
        if (Regex("""\bDispatchers\.(Main|IO|Default|Unconfined)""").containsMatchIn(code) && !f.path.startsWith("app/src/main/kotlin/app/cayresim/shell/di/"))
            out += Violation("R16", f.path, "Dispatchers.* ausserhalb des DI-Moduls")
        // Namensregeln
        out += naming(f, layer, code)
        return out
    }

    /** R28: Klassen der Tonaufnahme, die nur `:core:audio` kennen darf. */
    private val AUDIO_CAPTURE = listOf(
        "android.media.AudioRecord",
        "android.media.MediaRecorder",
        "android.media.MicrophoneInfo",
        "android.media.MicrophoneDirection",
        "android.media.AudioDeviceInfo",
    )

    /**
     * Ausnahme A1 (bestaetigt 07.10.2026, erweitert 10.10.2026 in S-005): Sucher-Screen darf CameraXViewfinder,
     * SurfaceRequest und MutableCoordinateTransformer (Umrechnung fuer Antippen zum Scharfstellen) nutzen.
     */
    private fun isA1(path: String, import: String) =
        path.startsWith("feature/camera/src/main/kotlin/app/cayresim/feature/camera/ui/") &&
            import in setOf(
                "androidx.camera.compose.CameraXViewfinder",
                "androidx.camera.core.SurfaceRequest",
                "androidx.camera.viewfinder.compose.MutableCoordinateTransformer",
            )

    private fun naming(f: SourceFile, layer: Layer, code: String): List<Violation> {
        val out = mutableListOf<Violation>()
        val plainClass = Regex("""(?m)^(?:internal\s+|public\s+)?(?:open\s+|abstract\s+)?class\s+(\w+)""")
        when (layer) {
            Layer.ENTITY -> plainClass.findAll(code).map { it.groupValues[1] }.filterNot { it.endsWith("Entity") }
                .forEach { out += Violation("Namen", f.path, "Klasse in :core:entity muss auf Entity enden: $it") }
            Layer.BOUNDARY -> Regex("""(?m)^(?:public\s+)?interface\s+(\w+)""").findAll(code).map { it.groupValues[1] }
                .filterNot { it.endsWith("Boundary") }.forEach { out += Violation("Namen", f.path, "Schnittstelle muss auf Boundary enden: $it") }
            Layer.ADAPTER -> Regex("""class\s+(\w+)(?:(?!\bclass\b)[^{])*:\s*(?:(?!\bclass\b)[^{])*\b(\w+Boundary)\b""").findAll(code).map { it.groupValues[1] }
                .filterNot { it.endsWith("Adapter") }.forEach { out += Violation("Namen", f.path, "Boundary-Implementierung muss auf Adapter enden: $it") }
            Layer.FEATURE_CONTROL, Layer.FEATURE_UI -> Regex("""class\s+(\w+)(?:(?!\bclass\b)[^{])*:\s*ViewModel\(""").findAll(code).map { it.groupValues[1] }
                .filterNot { it.endsWith("ViewModel") }.forEach { out += Violation("Namen", f.path, "ViewModel muss auf ViewModel enden: $it") }
            Layer.CONTROL_CORE -> plainClass.findAll(code).map { it.groupValues[1] }.filter { it.startsWith("Take") || it.startsWith("SelfTest") && !it.contains("Item") && !it.contains("Report") }
                .filterNot { it.endsWith("UseCase") }.forEach { out += Violation("Namen", f.path, "UseCase muss auf UseCase enden: $it") }
            else -> Unit
        }
        if (layer == Layer.FEATURE_UI && Regex("""class\s+\w+(?:(?!\bclass\b)[^{])*:\s*ViewModel\(""").containsMatchIn(code))
            out += Violation("Namen", f.path, "ViewModel gehoert ins Paket control, nicht ui")
        return out
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)//.*$"""), "")
            .replace(Regex("""(?m)^import\s+.*$"""), "")
}
