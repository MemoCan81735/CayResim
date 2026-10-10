package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.AudioFileBoundary
import app.cayresim.core.boundary.AudioSourceKey
import app.cayresim.core.boundary.InputDeviceKind
import app.cayresim.core.boundary.InputDeviceSnapshot
import app.cayresim.core.boundary.MicCapture
import app.cayresim.core.boundary.MicFailure
import app.cayresim.core.boundary.MicInventorySnapshot
import app.cayresim.core.boundary.MicLocationKey
import app.cayresim.core.boundary.MicPatternKey
import app.cayresim.core.boundary.MicInfoSnapshot
import app.cayresim.core.boundary.MicRecordResult
import app.cayresim.core.boundary.MicRequest
import app.cayresim.core.boundary.MicrophoneBoundary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mikrofon im Speicher. [sound] liefert je Anfrage das PCM (verschraenkt); [failures] laesst Quellen scheitern.
 * [open] zaehlt laufende Aufnahmen wie der echte Adapter: freigegeben wird in genau einem NonCancellable-Block.
 */
class FakeMicrophoneBoundary(
    var permission: Boolean = true,
    var inventory: MicInventorySnapshot = defaultInventory(),
    var recordDelayMillis: Long = 0,
) : MicrophoneBoundary {
    val requests = mutableListOf<MicRequest>()
    val failures = mutableMapOf<AudioSourceKey, MicFailure>()
    var channelsFor: (MicRequest) -> Int = { it.channels }
    var sound: (MicRequest, Int) -> ShortArray = { r, ch -> ShortArray(r.sampleRate * r.millis / 1000 * ch) }
    var open = 0
        private set
    var maxOpen = 0
        private set
    /** Wird bei jeder Aufnahme aufgerufen (z. B. um eine Uhr weiterzustellen). */
    var onRecord: (MicRequest) -> Unit = {}
    /** Zeitbezug der Aufnahme (S-010). */
    var startBootNanos: Long? = 1_000_000_000L
    var timeExact: Boolean = true

    override fun hasPermission() = permission
    override suspend fun inventory() = inventory

    override suspend fun record(request: MicRequest): MicRecordResult {
        requests += request
        onRecord(request)
        if (!permission) return MicRecordResult.Failed(MicFailure.NO_PERMISSION)
        failures[request.source]?.let { return MicRecordResult.Failed(it) }
        var opened = false
        try {
            open++; opened = true; maxOpen = maxOf(maxOpen, open)
            if (recordDelayMillis > 0) delay(recordDelayMillis)
            val ch = channelsFor(request)
            return MicRecordResult.Ok(MicCapture(request, request.sampleRate, ch, sound(request, ch), 1, listOf(1), startBootNanos, timeExact))
        } finally {
            withContext(NonCancellable) { if (opened) open-- }
        }
    }

    companion object {
        fun defaultInventory() = MicInventorySnapshot(
            microphones = listOf(
                MicInfoSnapshot(1, 1, "bottom", MicLocationKey.MAINBODY, MicPatternKey.OMNI, listOf(0.035f, 0.002f, 0f), -38f),
                MicInfoSnapshot(2, 1, "top", MicLocationKey.MAINBODY, MicPatternKey.OMNI, listOf(0.035f, 0.158f, 0f), -38f),
                MicInfoSnapshot(3, 2, "back", MicLocationKey.MAINBODY, MicPatternKey.OMNI, listOf(0.02f, 0.14f, 0.008f), null),
            ),
            devices = listOf(
                InputDeviceSnapshot(1, InputDeviceKind.BUILTIN_MIC, "bottom", listOf(1, 2)),
                InputDeviceSnapshot(2, InputDeviceKind.BUILTIN_MIC, "back", listOf(1, 2)),
                InputDeviceSnapshot(3, InputDeviceKind.TELEPHONY, "", listOf(1)),
            ),
            unprocessedSupported = true,
        )
    }
}

/** Tondateien im Speicher. [failSave] simuliert vollen Speicher. */
class FakeAudioFileBoundary : AudioFileBoundary {
    val files = linkedMapOf<String, ByteArray>()
    var failFolder = false
    var failSave = false
    override suspend fun newFolder(prefix: String): String? = if (failFolder) null else "Recordings/CayResim/$prefix-1"
    override suspend fun saveWav(folder: String, fileName: String, wav: ByteArray): String? {
        if (failSave) return null
        files["$folder/$fileName"] = wav
        return "content://fake/$folder/$fileName"
    }
}
