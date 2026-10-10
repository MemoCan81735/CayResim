package app.cayresim.core.control

import app.cayresim.core.boundary.AudioFileBoundary
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.InputDeviceKind
import app.cayresim.core.boundary.MicDirectionKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicInventorySnapshot
import app.cayresim.core.boundary.MicLocationKey
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicrophoneBoundary
import app.cayresim.core.pure.AudioMath
import app.cayresim.core.pure.Clock
import app.cayresim.core.pure.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/** Phasen der Klatsch-Probe in fester Reihenfolge; das Handy liegt quer. */
enum class ClapPhase { LEFT, RIGHT, FRONT }

/** Was eine Quelle ergab: Kennzahlen und Datei, oder der Grund, warum nicht aufgenommen wurde. */
sealed interface SourceOutcome {
    data class Recorded(
        val stats: AudioMath.RecordingStats,
        val routedDeviceId: Int?,
        val activeMicIds: List<Int>,
        val fileUri: String?,
    ) : SourceOutcome
    data class Failed(val reason: MicFailure) : SourceOutcome
}

data class SourceResult(val request: MicRequest, val outcome: SourceOutcome)

data class ClapResult(val phase: ClapPhase, val claps: List<AudioMath.Clap>, val fileUri: String?, val failure: MicFailure? = null)

/** Ergebnis eines Laufs; nur Zahlen, Schluessel und Enums (R23). */
data class MicTestReport(
    val inventory: MicInventorySnapshot?,
    val sources: List<SourceResult>,
    /** Quelle der Klatsch-Probe; null, wenn keine zwei verschiedenen Kanaele lieferte. */
    val clapSource: MicRequest?,
    val claps: List<ClapResult>,
    val spacingMeters: Double,
    /** true: Abstand aus den gemeldeten Mikrofonpositionen, false: angenommen. */
    val spacingMeasured: Boolean,
    /** Dauer des Quellen-Durchlaufs ohne Klatsch-Probe (K14). */
    val sourcesMillis: Long,
    val folder: String?,
    val failure: MicFailure? = null,
    /** Mindestens eine Datei konnte nicht gespeichert werden. */
    val storageFailed: Boolean = false,
)

sealed interface MicTestEvent {
    data object Inventory : MicTestEvent
    data class Source(val index: Int, val total: Int, val request: MicRequest) : MicTestEvent
    data class ClapPrompt(val phase: ClapPhase, val seconds: Int) : MicTestEvent
    data class Done(val report: MicTestReport) : MicTestEvent
}

/**
 * Mikrofon-Test (S-008): welche Mikrofone das Geraet meldet, welche Audioquellen Stereo und unbearbeiteten Ton liefern,
 * und ob sich aus einer Klatsch-Probe eine Richtung ablesen laesst. Jede Aufnahme wird als WAV gespeichert.
 * Abbruch (Bildschirm verlassen) beendet den Flow; das Mikrofon gibt der Adapter frei (R28). Rechnen und Kodieren
 * laufen auf dem ComputeDispatcher, nicht im Dispatcher des Sammlers (Zweitpruefung S-008: sonst auf Main).
 */
class MicTestUseCase @Inject constructor(
    private val mic: MicrophoneBoundary,
    private val files: AudioFileBoundary,
    private val clock: Clock,
    @ComputeDispatcher private val compute: CoroutineDispatcher,
) {
    fun run(withClaps: Boolean = true): Flow<MicTestEvent> = flow {
        if (!mic.hasPermission()) {
            emit(MicTestEvent.Done(MicTestReport(null, emptyList(), null, emptyList(), DEFAULT_SPACING, false, 0, null, MicFailure.NO_PERMISSION)))
            return@flow
        }
        emit(MicTestEvent.Inventory)
        val inv = mic.inventory()
        val folder = files.newFolder(FOLDER_PREFIX)
        var storageFailed = folder == null
        val plan = plan(inv)
        val sources = mutableListOf<SourceResult>()
        val t0 = clock.nowMillis()
        for ((i, req) in plan.withIndex()) {
            emit(MicTestEvent.Source(i, plan.size, req))
            if (req.source == AudioSourceKey.UNPROCESSED && !inv.unprocessedSupported) {
                sources += SourceResult(req, SourceOutcome.Failed(MicFailure.NOT_OFFERED)); continue
            }
            when (val r = mic.record(req)) {
                is MicRecordResult.Failed -> sources += SourceResult(req, SourceOutcome.Failed(r.reason))
                is MicRecordResult.Ok -> {
                    val c = r.capture
                    val uri = folder?.let { files.saveWav(it, fileName(i, req), Wav.encode(c.pcm, c.sampleRate, c.channels)) }
                    if (folder != null && uri == null) storageFailed = true
                    sources += SourceResult(req, SourceOutcome.Recorded(AudioMath.analyze(c.pcm, c.channels), c.routedDeviceId, c.activeMicIds, uri))
                }
            }
        }
        val sourcesMillis = clock.nowMillis() - t0
        val clapSource = chooseClapSource(sources)
        val (spacing, measured) = spacing(inv)
        val claps = mutableListOf<ClapResult>()
        if (withClaps && clapSource != null) {
            for (phase in ClapPhase.entries) {
                emit(MicTestEvent.ClapPrompt(phase, CLAP_SECONDS))
                when (val r = mic.record(clapSource.copy(millis = CLAP_SECONDS * 1000))) {
                    is MicRecordResult.Failed -> claps += ClapResult(phase, emptyList(), null, r.reason)
                    is MicRecordResult.Ok -> {
                        val c = r.capture
                        val uri = folder?.let { files.saveWav(it, "klatschen-${phase.name}-$FORMAT.wav", Wav.encode(c.pcm, c.sampleRate, c.channels)) }
                        if (folder != null && uri == null) storageFailed = true
                        claps += ClapResult(phase, directions(c.pcm, c.channels, c.sampleRate, spacing), uri)
                    }
                }
            }
        }
        emit(MicTestEvent.Done(MicTestReport(inv, sources, clapSource, claps, spacing, measured, sourcesMillis, folder, null, storageFailed)))
    }.flowOn(compute)

    companion object {
        const val FOLDER_PREFIX = "Mikrotest"
        const val FORMAT = "v1"
        const val CLAP_SECONDS = 4
        const val DEFAULT_SPACING = 0.15
        const val MAX_DEVICES = 4

        /** Feste Reihenfolge: Quellen, dann Richtungen, dann jedes eingebaute Mikrofon einzeln (hoechstens 4). */
        fun plan(inv: MicInventorySnapshot): List<MicRequest> =
            AudioSourceKey.entries.map { MicRequest(it) } +
                listOf(MicDirectionKey.TOWARDS_USER, MicDirectionKey.AWAY_FROM_USER).map { MicRequest(AudioSourceKey.MIC, direction = it) } +
                inv.devices.filter { it.kind == InputDeviceKind.BUILTIN_MIC }.take(MAX_DEVICES).map { MicRequest(AudioSourceKey.MIC, deviceId = it.id) }

        /** Erste Quelle mit zwei verschiedenen Kanaelen, roh zuerst (K11). */
        fun chooseClapSource(sources: List<SourceResult>): MicRequest? =
            listOf(AudioSourceKey.UNPROCESSED, AudioSourceKey.VOICE_RECOGNITION, AudioSourceKey.MIC).firstNotNullOfOrNull { key ->
                sources.firstOrNull { s ->
                    s.request.source == key && s.request.deviceId == null && s.request.direction == MicDirectionKey.NONE &&
                        (s.outcome as? SourceOutcome.Recorded)?.stats?.distinctChannels == true
                }?.request
            }

        /** Groesster Abstand zweier Mikrofone im Gehaeuse, sonst 15 cm (angenommen). */
        fun spacing(inv: MicInventorySnapshot): Pair<Double, Boolean> {
            val pos = inv.microphones.filter { it.location == MicLocationKey.MAINBODY }.mapNotNull { it.positionMeters }.filter { it.size == 3 }
            var best = 0.0
            for (i in pos.indices) for (j in i + 1 until pos.size) {
                val d = sqrt((0..2).sumOf { k -> ((pos[i][k] - pos[j][k]) * (pos[i][k] - pos[j][k])).toDouble() })
                best = maxOf(best, d)
            }
            return if (best in 0.02..0.4) best to true else DEFAULT_SPACING to false
        }

        fun fileName(index: Int, r: MicRequest): String = buildString {
            append("%02d-".format(index + 1)); append(r.source.name)
            if (r.direction != MicDirectionKey.NONE) append("-").append(r.direction.name)
            r.deviceId?.let { append("-geraet").append(it) }
            append("-").append(FORMAT).append(".wav")
        }

        fun directions(pcm: ShortArray, channels: Int, sampleRate: Int, spacing: Double): List<AudioMath.Clap> {
            if (channels < 2) return emptyList()
            val ch = AudioMath.deinterleave(pcm, channels)
            val mono = FloatArray(ch[0].size) { maxOf(abs(ch[0][it]), abs(ch[1][it])) }
            return AudioMath.clapDirections(ch[0], ch[1], sampleRate, spacing, AudioMath.findClaps(mono, sampleRate))
        }
    }
}
