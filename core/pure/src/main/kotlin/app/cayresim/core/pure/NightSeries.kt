package app.cayresim.core.pure

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * S-011 (V3): Archiv einer Nachtserie zum Nachmessen. Eine ZIP-Datei mit `meta.json` (Formatversion [FORMAT]),
 * Helligkeit je Bild (`y-NNN.bin`, 1 Byte je Pixel), drei Farbbildern und der Lage (`lage.csv`).
 * Das Format liegt hier, das Speichern im Daten-Adapter, das Lesen nutzen Tests und `tools/nacht-serie`.
 */
object NightSeries {
    const val FORMAT = 1
    const val META = "meta.json"
    const val LAGE = "lage.csv"
    const val RGB_FIRST = "rgb-first.bin"
    const val RGB_MID = "rgb-mid.bin"
    const val RGB_LAST = "rgb-last.bin"

    fun lumaName(index: Int) = String.format(Locale.ROOT, "y-%03d.bin", index)

    /** Helligkeit je Pixel wie bei der Ausrichtung ([FrameAligner.lumaAt]). */
    fun luma(rgb: ByteArray, pixels: Int): ByteArray = ByteArray(pixels) { FrameAligner.lumaAt(rgb, it * 3).toByte() }

    /** Kenndaten der Aufnahme fuer [metaJson]; nur Zahlen. */
    data class Info(
        val width: Int, val height: Int, val rotation: Int,
        val exposureNs: Long?, val iso: Int?, val meterExposureNs: Long?, val meterIso: Int?,
        val durationMs: Long?, val used: Int, val dropped: Int, val gain: Float, val maxShake: Int,
        /** Zeitstempel je Eingangsbild (ns seit dem Einschalten), null wo unbekannt. */
        val timestampsNs: List<Long?>,
        val diagnosis: NightDiagnosis? = null,
    )

    fun metaJson(info: Info, records: List<NightMerge.FrameRecord>): String = buildString {
        fun n(v: Number?) = when (v) {
            null -> "null"
            is Float -> if (v.isFinite()) String.format(Locale.ROOT, "%.6g", v) else "null"
            is Double -> if (v.isFinite()) String.format(Locale.ROOT, "%.6g", v) else "null"
            else -> v.toString()
        }
        append("{\n  \"format\": ").append(FORMAT).append(",\n")
        append("  \"width\": ").append(info.width).append(", \"height\": ").append(info.height).append(", \"rotation\": ").append(info.rotation).append(",\n")
        append("  \"exposureNs\": ").append(n(info.exposureNs)).append(", \"iso\": ").append(n(info.iso)).append(",\n")
        append("  \"meterExposureNs\": ").append(n(info.meterExposureNs)).append(", \"meterIso\": ").append(n(info.meterIso)).append(",\n")
        append("  \"durationMs\": ").append(n(info.durationMs)).append(", \"used\": ").append(info.used).append(", \"dropped\": ").append(info.dropped).append(",\n")
        append("  \"gain\": ").append(n(info.gain)).append(", \"maxShake\": ").append(info.maxShake).append(",\n")
        info.diagnosis?.let { d ->
            append("  \"diagnosis\": {\"floor\": ").append(d.floor).append(", \"signal\": ").append(n(d.signal)).append(", \"threshold\": ").append(n(d.threshold))
                .append(", \"noise\": ").append(n(d.noise)).append(", \"median\": ").append(n(d.median)).append(", \"zeroShare\": ").append(n(d.zeroShare)).append("},\n")
        }
        append("  \"frames\": [\n")
        val count = maxOf(records.size, info.timestampsNs.size)
        for (i in 0 until count) {
            val r = records.firstOrNull { it.index == i }
            append("    {\"index\": ").append(i).append(", \"timestampNs\": ").append(n(info.timestampsNs.getOrNull(i)))
            if (r != null) {
                append(", \"dx\": ").append(r.dx).append(", \"dy\": ").append(r.dy).append(", \"shiftRejected\": ").append(r.shiftRejected)
                    .append(", \"dropped\": ").append(r.dropped).append(", \"reference\": ").append(r.reference)
                    .append(", \"sharpness\": ").append(n(r.sharpness)).append(", \"meanLuma\": ").append(n(r.meanLuma)).append(", \"zeroShare\": ").append(n(r.zeroShare))
            }
            append("}").append(if (i < count - 1) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    /** Schreibt Eintraege in eine ZIP-Datei; schnelle Packstufe, weil waehrend der Serie geschrieben wird (R27). */
    class ZipWriter(out: OutputStream, level: Int = Deflater.BEST_SPEED) : AutoCloseable {
        private val zip = ZipOutputStream(out).apply { setLevel(level) }
        var bytesIn = 0L; private set

        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            bytesIn += bytes.size
        }

        override fun close() = zip.close()
    }

    class Archive(val format: Int, val meta: String, val entries: Map<String, ByteArray>)

    /** Liest ein Archiv; null bei fehlenden Kenndaten, fremder Formatversion oder kaputter Datei (R26). */
    fun read(input: InputStream): Archive? = runCatching {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        val meta = entries[META]?.toString(Charsets.UTF_8) ?: return null
        val format = Regex("\"format\"\\s*:\\s*(\\d+)").find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (format != FORMAT) return null
        Archive(format, meta, entries)
    }.getOrNull()
}
