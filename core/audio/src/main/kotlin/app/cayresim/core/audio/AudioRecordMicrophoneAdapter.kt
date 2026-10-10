package app.cayresim.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.media.MicrophoneInfo
import android.os.SystemClock
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.InputDeviceKind
import app.cayresim.core.boundary.InputDeviceSnapshot
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.MicCapture
import app.cayresim.core.boundary.MicDirectionKey
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicInfoSnapshot
import app.cayresim.core.boundary.MicInventorySnapshot
import app.cayresim.core.boundary.MicLocationKey
import app.cayresim.core.boundary.MicPatternKey
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicrophoneBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mikrofon ueber AudioRecord (S-008, R28). Eine Aufnahme zur Zeit (Mutex); jede Aufnahme gibt das Mikrofon in genau
 * einem NonCancellable-Block frei, auch bei Abbruch oder Fehler. Gelesen wird auf dem IoDispatcher in Stuecken von
 * 10 ms, zwischen denen auf Abbruch geprueft wird (R17). Die ersten 50 ms werden verworfen (Einschwingen). Jede Aufnahme hat eine Frist (Dauer plus 1 s).
 */
@Singleton
class AudioRecordMicrophoneAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MicrophoneBoundary {
    private val lock = Mutex()
    private val manager: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    /** Offene Aufnahmen; fuer den Geraetetest K13 (muss nach jeder Aufnahme 0 sein). */
    @Volatile var openRecordings = 0
        private set

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun inventory(): MicInventorySnapshot = withContext(io) {
        val am = manager ?: return@withContext MicInventorySnapshot(emptyList(), emptyList(), false)
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
        val mics = runCatching { am.microphones }.getOrDefault(emptyList()).map { m ->
            MicInfoSnapshot(
                id = m.id,
                deviceId = devices.firstOrNull { it.address == m.address && it.type == m.type }?.id,
                address = m.address,
                location = locationOf(m.location),
                pattern = patternOf(m.directionality),
                positionMeters = m.position.takeIf { it.x != MicrophoneInfo.POSITION_UNKNOWN.x }?.let { listOf(it.x, it.y, it.z) },
                sensitivityDb = m.sensitivity.takeIf { it != MicrophoneInfo.SENSITIVITY_UNKNOWN },
            )
        }
        val unprocessed = runCatching { am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) }.getOrNull() == "true"
        MicInventorySnapshot(mics, devices.map { InputDeviceSnapshot(it.id, kindOf(it.type), it.address, it.channelCounts.toList()) }, unprocessed)
    }

    override suspend fun record(request: MicRequest): MicRecordResult = lock.withLock {
        if (!hasPermission()) return@withLock MicRecordResult.Failed(MicFailure.NO_PERMISSION)
        if (request.millis <= 0 || request.channels !in 1..2 || request.sampleRate <= 0) return@withLock MicRecordResult.Failed(MicFailure.INIT_FAILED)
        withContext(io) { recordLocked(request) }
    }

    @SuppressLint("MissingPermission") // geprueft in record() direkt davor (hasPermission)
    private suspend fun recordLocked(request: MicRequest): MicRecordResult {
        val mask = if (request.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(request.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return MicRecordResult.Failed(MicFailure.INIT_FAILED)
        var rec: AudioRecord? = null
        var opened = false
        try {
            rec = try {
                AudioRecord.Builder()
                    .setAudioSource(sourceOf(request.source))
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(request.sampleRate).setChannelMask(mask).build())
                    .setBufferSizeInBytes(maxOf(min * 4, request.sampleRate * request.channels * 2 / 5))
                    .build()
            } catch (_: SecurityException) {
                return MicRecordResult.Failed(MicFailure.NO_PERMISSION)
            } catch (_: Exception) {
                return MicRecordResult.Failed(MicFailure.INIT_FAILED)
            }
            opened = true; openRecordings++
            if (rec.state != AudioRecord.STATE_INITIALIZED) return MicRecordResult.Failed(MicFailure.INIT_FAILED)
            request.deviceId?.let { id ->
                manager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.id == id }?.let { rec.setPreferredDevice(it) }
            }
            when (request.direction) {
                MicDirectionKey.TOWARDS_USER -> rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                MicDirectionKey.AWAY_FROM_USER -> rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER)
                MicDirectionKey.NONE -> Unit
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MicRecordResult.Failed(MicFailure.INIT_FAILED)
            val channels = rec.format.channelCount.takeIf { it in 1..8 } ?: request.channels
            val chunk = request.sampleRate / 100 * channels
            // Frist (R27, Zweitpruefung S-008): Dauer plus Einschwingen plus 1 s; danach READ_FAILED statt endlos lesen.
            // Nicht blockierend gelesen, damit auch ein haengendes Geraet die Frist nicht aushebelt.
            val deadline = SystemClock.elapsedRealtime() + request.millis + WARMUP_MS + GRACE_MS
            if (!fill(rec, ShortArray(request.sampleRate * WARMUP_MS / 1000 * channels), chunk, deadline)) {
                return MicRecordResult.Failed(MicFailure.READ_FAILED)
            }
            val pcm = ShortArray((request.sampleRate.toLong() * request.millis / 1000).toInt() * channels)
            if (!fill(rec, pcm, chunk, deadline)) return MicRecordResult.Failed(MicFailure.READ_FAILED)
            val routed = rec.routedDevice?.id
            val active = runCatching { rec.activeMicrophones.map { it.id } }.getOrDefault(emptyList())
            return MicRecordResult.Ok(MicCapture(request, rec.sampleRate, channels, pcm, routed, active))
        } finally {
            // R28: genau ein Freigabeblock, auch bei Abbruch
            withContext(NonCancellable) {
                val r = rec
                if (opened && r != null) {
                    runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
                    runCatching { r.release() }
                    openRecordings--
                }
            }
        }
    }

    /** Liest [buf] voll; false bei Lesefehler oder ueberschrittener Frist. Prueft zwischen den Stuecken auf Abbruch (R17). */
    private suspend fun fill(rec: AudioRecord, buf: ShortArray, chunk: Int, deadline: Long): Boolean {
        var pos = 0
        while (pos < buf.size) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() > deadline) return false
            val n = rec.read(buf, pos, minOf(chunk, buf.size - pos), AudioRecord.READ_NON_BLOCKING)
            if (n < 0) return false
            if (n == 0) delay(POLL_MS) else pos += n
        }
        return true
    }

    internal companion object {
        const val WARMUP_MS = 50
        const val GRACE_MS = 1_000
        const val POLL_MS = 5L

        fun sourceOf(k: AudioSourceKey) = when (k) {
            AudioSourceKey.MIC -> MediaRecorder.AudioSource.MIC
            AudioSourceKey.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            AudioSourceKey.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSourceKey.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        }

        fun locationOf(v: Int) = when (v) {
            MicrophoneInfo.LOCATION_MAINBODY -> MicLocationKey.MAINBODY
            MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> MicLocationKey.MAINBODY_MOVABLE
            MicrophoneInfo.LOCATION_PERIPHERAL -> MicLocationKey.PERIPHERAL
            else -> MicLocationKey.UNKNOWN
        }

        fun patternOf(v: Int) = when (v) {
            MicrophoneInfo.DIRECTIONALITY_OMNI -> MicPatternKey.OMNI
            MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> MicPatternKey.BI_DIRECTIONAL
            MicrophoneInfo.DIRECTIONALITY_CARDIOID -> MicPatternKey.CARDIOID
            MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> MicPatternKey.HYPER_CARDIOID
            MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> MicPatternKey.SUPER_CARDIOID
            else -> MicPatternKey.UNKNOWN
        }

        fun kindOf(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceKind.BUILTIN_MIC
            AudioDeviceInfo.TYPE_TELEPHONY -> InputDeviceKind.TELEPHONY
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputDeviceKind.WIRED
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputDeviceKind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> InputDeviceKind.BLUETOOTH
            else -> InputDeviceKind.OTHER
        }
    }
}
