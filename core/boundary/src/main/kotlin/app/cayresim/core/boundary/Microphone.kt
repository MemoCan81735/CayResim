package app.cayresim.core.boundary

import kotlinx.coroutines.flow.Flow

/** Audioquellen von Android, soweit der Mikrofon-Test sie prueft (S-008). */
enum class AudioSourceKey { MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED }

/** Bevorzugte Richtung (`setPreferredMicrophoneDirection`); NONE = nicht gesetzt. */
enum class MicDirectionKey { NONE, TOWARDS_USER, AWAY_FROM_USER }

enum class MicLocationKey { MAINBODY, MAINBODY_MOVABLE, PERIPHERAL, UNKNOWN }

enum class MicPatternKey { OMNI, BI_DIRECTIONAL, CARDIOID, HYPER_CARDIOID, SUPER_CARDIOID, UNKNOWN }

enum class InputDeviceKind { BUILTIN_MIC, TELEPHONY, WIRED, USB, BLUETOOTH, OTHER }

/** Ein Mikrofon laut `AudioManager.getMicrophones()`; Position in Metern, null wenn das Geraet sie nicht meldet. */
data class MicInfoSnapshot(
    val id: Int,
    val deviceId: Int?,
    val address: String,
    val location: MicLocationKey,
    val pattern: MicPatternKey,
    val positionMeters: List<Float>?,
    val sensitivityDb: Float?,
)

/** Ein Eingabegeraet laut `getDevices(GET_DEVICES_INPUTS)`. */
data class InputDeviceSnapshot(val id: Int, val kind: InputDeviceKind, val address: String, val channelCounts: List<Int>)

data class MicInventorySnapshot(
    val microphones: List<MicInfoSnapshot>,
    val devices: List<InputDeviceSnapshot>,
    val unprocessedSupported: Boolean,
)

/** Eine Aufnahme: Quelle, optional Geraet und Richtung, Dauer; 16 Bit PCM. */
data class MicRequest(
    val source: AudioSourceKey,
    val deviceId: Int? = null,
    val direction: MicDirectionKey = MicDirectionKey.NONE,
    val millis: Int = 2_000,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
)

/** Aufgenommener Ton, verschraenkt je Frame. [routedDeviceId] und [activeMicIds]: was Android tatsaechlich nutzte. */
class MicCapture(
    val request: MicRequest,
    val sampleRate: Int,
    val channels: Int,
    val pcm: ShortArray,
    val routedDeviceId: Int?,
    val activeMicIds: List<Int>,
    /** Zeit des ersten Frames in ns seit dem Einschalten (Zeitbasis der Sensoren, S-010); null, wenn unbekannt. */
    val startBootNanos: Long? = null,
    /** true: aus dem Zeitstempel der Aufnahme; false: grob aus der Startzeit. */
    val timeExact: Boolean = false,
)

/** READ_FAILED auch, wenn die Aufnahme ihre Frist ueberschreitet (Dauer plus 1 s, R27). */
enum class MicFailure { NO_PERMISSION, NOT_OFFERED, INIT_FAILED, READ_FAILED }

sealed interface MicRecordResult {
    class Ok(val capture: MicCapture) : MicRecordResult
    data class Failed(val reason: MicFailure) : MicRecordResult
}

/** Ein Stueck laufender Ton (S-018): verschraenkt je Frame, 16 Bit. Das Feld gehoert dem Empfaenger. */
class MicChunk(val sampleRate: Int, val channels: Int, val pcm: ShortArray)

/** Meldungen des Tonstroms; nach [Failed] endet er (R24). */
sealed interface MicStreamEvent {
    class Chunk(val chunk: MicChunk) : MicStreamEvent
    data class Failed(val reason: MicFailure) : MicStreamEvent
}

/**
 * Einzige Sicht der App auf das Mikrofon (R28). Eine Aufnahme zur Zeit, ob einzeln oder als Strom; das Mikrofon wird
 * nach jeder Aufnahme freigegeben, auch bei Abbruch. Fehler als Werte (R24).
 */
interface MicrophoneBoundary {
    fun hasPermission(): Boolean
    suspend fun inventory(): MicInventorySnapshot
    suspend fun record(request: MicRequest): MicRecordResult

    /**
     * Laufender Ton in Stuecken von [chunkMillis] (S-018, R25), bis der Sammler abbricht; dann wird das Mikrofon
     * freigegeben. [MicRequest.millis] hat hier keine Bedeutung. Ein Fehler kommt als [MicStreamEvent.Failed], danach
     * endet der Strom. Ist der Sammler zu langsam, gehen Stuecke verloren statt sich zu stauen.
     */
    fun stream(request: MicRequest, chunkMillis: Int = 100): Flow<MicStreamEvent>
}

/** Ablage fuer Tondateien in der Medienablage (Recordings/CayResim). */
interface AudioFileBoundary {
    /** Legt einen neuen Ordner fuer einen Testlauf an und liefert seinen relativen Pfad, null bei Fehler. */
    suspend fun newFolder(prefix: String): String?

    /** Speichert eine WAV-Datei im Ordner; liefert die URI oder null bei Fehler. */
    suspend fun saveWav(folder: String, fileName: String, wav: ByteArray): String?
}
