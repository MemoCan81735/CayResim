package app.cayresim.core.boundary

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
)

enum class MicFailure { NO_PERMISSION, NOT_OFFERED, INIT_FAILED, READ_FAILED, CANCELLED }

sealed interface MicRecordResult {
    class Ok(val capture: MicCapture) : MicRecordResult
    data class Failed(val reason: MicFailure) : MicRecordResult
}

/**
 * Einzige Sicht der App auf das Mikrofon (R28). Eine Aufnahme zur Zeit; das Mikrofon wird nach jeder Aufnahme
 * freigegeben, auch bei Abbruch. Fehler als Werte (R24).
 */
interface MicrophoneBoundary {
    fun hasPermission(): Boolean
    suspend fun inventory(): MicInventorySnapshot
    suspend fun record(request: MicRequest): MicRecordResult
}

/** Ablage fuer Tondateien in der Medienablage (Recordings/CayResim). */
interface AudioFileBoundary {
    /** Legt einen neuen Ordner fuer einen Testlauf an und liefert seinen relativen Pfad, null bei Fehler. */
    suspend fun newFolder(prefix: String): String?

    /** Speichert eine WAV-Datei im Ordner; liefert die URI oder null bei Fehler. */
    suspend fun saveWav(folder: String, fileName: String, wav: ByteArray): String?
}
