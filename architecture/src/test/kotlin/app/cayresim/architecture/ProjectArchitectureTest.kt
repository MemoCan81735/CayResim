package app.cayresim.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Testebene 7: das ganze Projekt gegen R1 bis R16 und die Namensregeln. */
class ProjectArchitectureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "core").isDirectory }

    private fun projectFiles(): List<SourceFile> = Konsist.scopeFromProject().files
        .map { SourceFile(File(it.path).relativeTo(root).invariantSeparatorsPath, it.text) }
        .filter { "/src/main/" in it.path && !it.path.startsWith("build-logic/") && !it.path.startsWith("config/") }

    @Test fun `Projekt hat keine Architekturverstoesse`() {
        val files = projectFiles()
        assertTrue(files.size > 20, "Konsist hat zu wenige Dateien gefunden: ${files.size}")
        val v = ArchitectureRules.check(files)
        assertEquals(emptyList(), v, v.joinToString("\n"))
    }

    @Test fun `Jede Hauptdatei ist einer Schicht zugeordnet`() {
        val unknown = projectFiles().filter { ArchitectureRules.layerOf(it.path) == ArchitectureRules.Layer.UNKNOWN }
        assertEquals(emptyList(), unknown.map { it.path })
    }

    @Test fun `Innere Module sind reine JVM-Module ohne Android-Plugin`() {
        listOf("core/pure", "core/entity", "core/boundary", "core/control").forEach {
            val build = File(root, "$it/build.gradle.kts").readText()
            assertTrue("cayresim.jvm" in build && "android" !in build.lowercase().replace("cayresim.jvm", ""), "$it muss rein JVM sein")
        }
    }
}
