package app.cayresim.core.data

import android.content.Context
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.boundary.NightPathSnapshot
import app.cayresim.core.pure.NightPath
import app.cayresim.core.pure.NightPathRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wahl des Nachtwegs als eine Zeile "v1 RAW RAW_OK" im App-Speicher (R26: Formatversion; Unbekanntes oder
 * Kaputtes ergibt den 8-Bit-Weg). Die Datei wird erst auf dem IO-Thread aufgeloest (R18).
 */
@Singleton
class FileNightPathAdapter internal constructor(
    fileProvider: () -> File,
    private val io: CoroutineDispatcher,
) : NightPathBoundary {
    internal constructor(file: File, io: CoroutineDispatcher) : this({ file }, io)

    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher) :
        this({ File(context.filesDir, "night-path.txt") }, io)

    private val file by lazy(fileProvider)

    override suspend fun load(): NightPathSnapshot = withContext(io) {
        val parts = runCatching { if (file.exists()) file.readText().trim().split(' ') else emptyList() }.getOrDefault(emptyList())
        if (parts.size < 2 || parts[0] != VERSION) return@withContext NightPathSnapshot()
        val path = NightPath.entries.firstOrNull { it.name == parts[1] } ?: return@withContext NightPathSnapshot()
        NightPathSnapshot(path, parts.getOrNull(2)?.let { r -> NightPathRule.Reason.entries.firstOrNull { it.name == r } })
    }

    override suspend fun save(snapshot: NightPathSnapshot) = withContext(io) {
        runCatching {
            FileOutputStream(file, false).use { out ->
                out.write("$VERSION ${snapshot.path.name} ${snapshot.reason?.name ?: "-"}\n".toByteArray())
                out.fd.sync()
            }
        }
        Unit
    }

    private companion object { const val VERSION = "v1" }
}
