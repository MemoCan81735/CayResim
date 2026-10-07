package app.cayresim.core.data

import android.content.Context
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.JournalSnapshot
import app.cayresim.core.boundary.SelfTestJournalBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protokoll als Textdatei im App-Speicher. Jede Zeile wird mit fsync geschrieben, damit sie
 * einen harten Neustart des Geraets uebersteht. Zeilen: "begin", "step <name>", "photo <uri>", "done".
 */
@Singleton
class FileSelfTestJournalAdapter internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
) : SelfTestJournalBoundary {
    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher) :
        this(File(context.filesDir, "selftest-journal.txt"), io)

    override suspend fun unfinished(): JournalSnapshot? = withContext(io) {
        val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())
        if (lines.isEmpty() || lines.last() == DONE) return@withContext null
        JournalSnapshot(
            lastStep = lines.lastOrNull { it.startsWith(STEP) }?.removePrefix(STEP),
            photoUris = lines.filter { it.startsWith(PHOTO) }.map { it.removePrefix(PHOTO) },
        )
    }

    override suspend fun begin() = withContext(io) { write(BEGIN + "\n", append = false) }
    override suspend fun step(name: String) = withContext(io) { write(STEP + name.replace('\n', ' ') + "\n") }
    override suspend fun photo(uri: String) = withContext(io) { write(PHOTO + uri + "\n") }
    override suspend fun finish() = withContext(io) { write(DONE + "\n") }

    private fun write(text: String, append: Boolean = true) {
        runCatching {
            FileOutputStream(file, append).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
        }
    }

    private companion object {
        const val STEP = "step "
        const val PHOTO = "photo "
        const val DONE = "done"
        const val BEGIN = "begin"
    }
}
