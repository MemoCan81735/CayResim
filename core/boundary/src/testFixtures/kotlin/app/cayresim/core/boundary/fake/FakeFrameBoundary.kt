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

    override fun trigger(mode: TriggerMode): Flow<Unit> { lastTriggerMode = mode; return fires }
}
