package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.BurstFailure
import app.cayresim.core.boundary.BurstResult
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.FrameBurst
import app.cayresim.core.boundary.TriggerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeFrameBoundary(private val camera: FakeCameraBoundary) : FrameBoundary {
    var allowed: Int? = null
    var fail: BurstFailure? = null
    val fires = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    var lastTriggerMode: TriggerMode? = null

    override suspend fun collect(count: Int): BurstResult {
        if (camera.state.value.status != app.cayresim.core.boundary.CameraStatus.RUNNING) return BurstResult.Failed(BurstFailure.NOT_READY)
        fail?.let { return BurstResult.Failed(it) }
        val n = minOf(count, allowed ?: count).coerceAtLeast(1)
        return BurstResult.Ok(FrameBurst(4, 3, List(n) { ByteArray(4 * 3 * 3) }), count)
    }

    /** Zahl der tatsaechlich gelieferten Einzelbilder des letzten Stroms. */
    var streamed = 0; private set

    override fun frames(maxCount: Int): Flow<app.cayresim.core.boundary.Frame> = kotlinx.coroutines.flow.flow {
        streamed = 0
        if (camera.state.value.status != app.cayresim.core.boundary.CameraStatus.RUNNING || fail != null) return@flow
        val n = minOf(maxCount, allowed ?: maxCount).coerceAtLeast(0)
        // gezaehlt beim Liefern: beendet der Empfaenger den Strom nach dem letzten Bild, zaehlt es trotzdem (S-006)
        repeat(n) { streamed++; emit(app.cayresim.core.boundary.Frame(4, 3, ByteArray(4 * 3 * 3))) }
    }

    /** RAW-Strom: null = wie [allowed]; 0 = RAW nicht moeglich (leerer Strom). */
    var rawAllowed: Int? = null
    var rawStreamed = 0; private set
    val rawCalls = mutableListOf<Triple<Int, Long, Int>>()

    override fun rawFrames(maxCount: Int, exposureNs: Long, iso: Int): Flow<app.cayresim.core.boundary.RawFrame> = kotlinx.coroutines.flow.flow {
        rawCalls += Triple(maxCount, exposureNs, iso); rawStreamed = 0
        if (camera.state.value.status != app.cayresim.core.boundary.CameraStatus.RUNNING || fail != null) return@flow
        val n = minOf(maxCount, rawAllowed ?: allowed ?: maxCount).coerceAtLeast(0)
        repeat(n) {
            rawStreamed++
            emit(app.cayresim.core.boundary.RawFrame(4, 2, 4, ShortArray(8) { 64 }, app.cayresim.core.boundary.CfaLayout.RGGB,
                floatArrayOf(64f, 64f, 64f, 64f), 1023f, floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        }
    }

    override fun trigger(mode: TriggerMode): Flow<Unit> { lastTriggerMode = mode; return fires }
}
