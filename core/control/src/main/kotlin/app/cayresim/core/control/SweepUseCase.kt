package app.cayresim.core.control

import app.cayresim.core.boundary.AudioFileBoundary
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicrophoneBoundary
import app.cayresim.core.boundary.MotionKind
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.pure.SweepMath
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.sqrt

enum class SweepRunFailure { NO_PERMISSION, NO_SENSOR, RECORD_FAILED }

/** Ergebnis einer Schwenk-Messung; nur Zahlen, Schluessel und Enums (R23). */
data class SweepRunReport(
    val analysis: SweepMath.SweepReport?,
    val failure: SweepRunFailure?,
    val micFailure: MicFailure? = null,
    /** true: Zeitbezug aus dem Zeitstempel der Aufnahme; false: grob. */
    val timeExact: Boolean = false,
    val fileUri: String? = null,
    val folder: String? = null,
    val storageFailed: Boolean = false,
)

sealed interface SweepEvent {
    /** Zweimal auf die Rueckseite tippen; die Aufnahme laeuft schon. */
    data class Tap(val seconds: Int) : SweepEvent
    /** Langsam schwenken. */
    data class Sweep(val seconds: Int) : SweepEvent
    data object Analyzing : SweepEvent
    data class Done(val report: SweepRunReport) : SweepEvent
}

/**
 * Schwenk-Messung (S-010): nimmt Ton und Lage gleichzeitig auf, wertet aus und speichert eine WAV mit den Lagewerten
 * ("lage", CSV) und Kenndaten ("meta", JSON) als Zusatzbloecken. Die Medienablage erlaubt unter Recordings nur
 * Tondateien; so bleibt alles in einer Datei. Abbruch beendet den Flow: Sensor und Mikrofon geben ihre Adapter frei
 * (R17, R28, R29). Rechnen auf dem ComputeDispatcher (R16, R18).
 */
class SweepUseCase @Inject constructor(
    private val mic: MicrophoneBoundary,
    private val sensors: MotionSensorBoundary,
    private val files: AudioFileBoundary,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
    fun run(): Flow<SweepEvent> = channelFlow {
        if (!mic.hasPermission()) { send(SweepEvent.Done(SweepRunReport(null, SweepRunFailure.NO_PERMISSION))); return@channelFlow }
        if (!sensors.availability().rotation) { send(SweepEvent.Done(SweepRunReport(null, SweepRunFailure.NO_SENSOR))); return@channelFlow }
        val folder = files.newFolder(FOLDER_PREFIX)
        send(SweepEvent.Tap(TAP_SECONDS))
        val samples = ArrayList<MotionSample>(4 * TOTAL_SECONDS * 100)
        val result = coroutineScope {
            // sofort anmelden, damit die Lage vor dem ersten Ton laeuft (sonst startete die Aufnahme zuerst)
            val sensorJob = launch(start = CoroutineStart.UNDISPATCHED) { sensors.samples(PERIOD_MICROS).collect { samples += it } }
            val prompt = launch { delay(TAP_SECONDS * 1000L); send(SweepEvent.Sweep(TOTAL_SECONDS - TAP_SECONDS)) }
            val r = mic.record(MicRequest(AudioSourceKey.MIC, millis = TOTAL_SECONDS * 1000))
            prompt.cancel(); sensorJob.cancelAndJoin()
            r
        }
        val capture = when (result) {
            is MicRecordResult.Failed -> {
                send(SweepEvent.Done(SweepRunReport(null, SweepRunFailure.RECORD_FAILED, result.reason, folder = folder))); return@channelFlow
            }
            is MicRecordResult.Ok -> result.capture
        }
        send(SweepEvent.Analyzing)
        val poses = poses(samples)
        val accel = samples.filter { it.kind == MotionKind.ACCELERATION }
            .map { SweepMath.AccelSample(it.nanos, sqrt((it.a * it.a + it.b * it.b + it.c * it.c).toDouble())) }
        val exact = capture.startBootNanos != null && capture.timeExact
        val startNanos = capture.startBootNanos ?: poses.firstOrNull()?.nanos ?: 0L
        val analysis = SweepMath.analyze(capture.pcm, capture.channels, capture.sampleRate, startNanos, poses, accel, TAP_SECONDS.toDouble())
        val wav = Wav.encode(capture.pcm, capture.sampleRate, capture.channels, linkedMapOf(
            "lage" to csv(samples).toByteArray(Charsets.UTF_8),
            "meta" to meta(capture.sampleRate, capture.channels, startNanos, exact, analysis).toByteArray(Charsets.UTF_8),
        ))
        val uri = folder?.let { files.saveWav(it, FILE_NAME, wav) }
        send(SweepEvent.Done(SweepRunReport(analysis, null, null, exact, uri, folder, storageFailed = uri == null)))
    }.flowOn(compute)

    companion object {
        const val FOLDER_PREFIX = "Schwenk"
        const val FILE_NAME = "schwenk-v1.wav"
        const val TAP_SECONDS = 3
        const val TOTAL_SECONDS = 25
        /** 100 Hz; bis 200 Hz ohne Berechtigung. */
        const val PERIOD_MICROS = 10_000

        fun poses(samples: List<MotionSample>): List<SweepMath.Pose> = samples.filter { it.kind == MotionKind.ROTATION }
            .sortedBy { it.nanos }
            .map { SweepMath.Pose(it.nanos, it.a.toDouble(), it.b.toDouble(), it.c.toDouble(), it.d.toDouble()) }

        /** Lagewerte als CSV, Formatversion in der ersten Zeile (R26). */
        fun csv(samples: List<MotionSample>): String = buildString {
            append("format=v1\n")
            append("art,zeit_ns,a,b,c,d\n")
            for (s in samples) append(s.kind.name).append(',').append(s.nanos).append(',').append(s.a).append(',')
                .append(s.b).append(',').append(s.c).append(',').append(s.d).append('\n')
        }

        /** Kenndaten und Ergebnis als JSON, Formatversion 1 (R26). */
        fun meta(sampleRate: Int, channels: Int, startNanos: Long, exact: Boolean, a: SweepMath.SweepReport): String {
            fun n(v: Double?) = v?.let { String.format(Locale.ROOT, "%.9g", it) } ?: "null"
            val e = a.estimate
            val ok = e as? SweepMath.Estimate.Ok
            return buildString {
                append("{\n")
                append("  \"format\": 1,\n")
                append("  \"sampleRate\": ").append(sampleRate).append(",\n")
                append("  \"channels\": ").append(channels).append(",\n")
                append("  \"startBootNanos\": ").append(startNanos).append(",\n")
                append("  \"timeExact\": ").append(exact).append(",\n")
                append("  \"tapSeconds\": ").append(TAP_SECONDS).append(",\n")
                append("  \"micAxisDevice\": [").append(SweepMath.MIC_AXIS_DEVICE.let { "${it.x}, ${it.y}, ${it.z}" }).append("],\n")
                append("  \"syncSeconds\": ").append(n(a.syncSeconds)).append(",\n")
                append("  \"sensorRateHz\": ").append(n(a.sensorRateHz)).append(",\n")
                append("  \"framesTotal\": ").append(a.framesTotal).append(",\n")
                append("  \"framesUsed\": ").append(a.framesUsed).append(",\n")
                append("  \"coverage\": ").append(n(e.coverage)).append(",\n")
                append("  \"failure\": ").append((e as? SweepMath.Estimate.Failed)?.let { "\"${it.reason.name}\"" } ?: "null").append(",\n")
                append("  \"direction\": ").append(ok?.direction?.let { "[${n(it.x)}, ${n(it.y)}, ${n(it.z)}]" } ?: "null").append(",\n")
                append("  \"spacingMeters\": ").append(n(ok?.spacingMeters)).append(",\n")
                append("  \"offsetSeconds\": ").append(n(ok?.offsetSeconds)).append(",\n")
                append("  \"residualSeconds\": ").append(n(ok?.residualSeconds)).append(",\n")
                append("  \"relAzimuthDeg\": ").append(n(a.relAzimuthDeg)).append(",\n")
                append("  \"relElevationDeg\": ").append(n(a.relElevationDeg)).append("\n")
                append("}\n")
            }
        }
    }
}
