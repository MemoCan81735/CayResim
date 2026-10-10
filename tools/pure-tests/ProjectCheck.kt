// Nur fuer tools/run-architecture-check.sh: prueft alle Hauptdateien gegen ArchitectureRules, ohne Konsist.
import app.cayresim.architecture.ArchitectureRules
import app.cayresim.architecture.SourceFile
import java.io.File
object ProjectCheck {
    @JvmStatic fun main(a: Array<String>) {
        val root = File(a[0])
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && "/build/" !in it.path && "/.git/" !in it.path }
            .map { SourceFile(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
            .filter { !it.path.startsWith("build-logic/") && !it.path.startsWith("config/") }.toList()
        val v = ArchitectureRules.check(files)
        val unknown = files.filter { ArchitectureRules.layerOf(it.path) == ArchitectureRules.Layer.UNKNOWN }.map { it.path }
        println("Dateien ${files.size}, Verstoesse ${v.size}, ohne Schicht ${unknown.size}"); v.forEach { println(it) }; unknown.forEach { println("ohne Schicht: $it") }
    }
}
