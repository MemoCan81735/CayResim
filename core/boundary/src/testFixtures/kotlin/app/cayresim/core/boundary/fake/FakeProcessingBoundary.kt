package app.cayresim.core.boundary.fake

import app.cayresim.core.boundary.ImageHandle
import app.cayresim.core.boundary.Look
import app.cayresim.core.boundary.ProcessFailure
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary

class FakeProcessingBoundary : ProcessingBoundary {
    val known = mutableSetOf<String>()
    var gpuFails = false
    val looksApplied = mutableListOf<Pair<String, Look>>()
    var lastTimelapse: List<String>? = null
    private var n = 0

    override suspend fun loadPreview(uri: String, maxPx: Int): ImageHandle? =
        if (uri in known && maxPx > 0) ImageHandle("bitmap:$uri", maxPx, maxPx * 3 / 4) else null

    override suspend fun applyLook(uri: String, look: Look): ProcessResult {
        if (uri !in known) return ProcessResult.Failed(ProcessFailure.SOURCE_MISSING)
        if (gpuFails) return ProcessResult.Failed(ProcessFailure.GPU)
        looksApplied += uri to look
        val out = "content://fake/look/${++n}"; known += out
        return ProcessResult.Saved(out)
    }

    val stacked = mutableListOf<Pair<Int, app.cayresim.core.boundary.StackMode>>()

    override suspend fun stack(burst: app.cayresim.core.boundary.FrameBurst, mode: app.cayresim.core.boundary.StackMode): ProcessResult {
        if (burst.frames.isEmpty()) return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        if (gpuFails) return ProcessResult.Failed(ProcessFailure.GPU)
        stacked += burst.frames.size to mode
        val out = "content://fake/stack/${++n}"; known += out
        return ProcessResult.Saved(out)
    }

    /** Bildzahl je Nachtaufnahme. */
    val nightRuns = mutableListOf<Int>()

    /** Haelt Nachtaufnahmen an, bis der Test sie freigibt; zaehlt jeden Start. */
    var nightGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var nightStarts = 0; private set

    override suspend fun night(frames: kotlinx.coroutines.flow.Flow<app.cayresim.core.boundary.Frame>): ProcessResult {
        nightStarts++
        nightGate?.await()
        var count = 0
        frames.collect { count++ }
        if (count == 0) return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        if (gpuFails) return ProcessResult.Failed(ProcessFailure.GPU)
        nightRuns += count
        val out = "content://fake/night/${++n}"; known += out
        return ProcessResult.Saved(out, "$count Bilder")
    }

    override suspend fun timelapse(photoUris: List<String>, photosPerSecond: Int): ProcessResult {
        if (photoUris.isEmpty() || photosPerSecond !in 1..60) return ProcessResult.Failed(ProcessFailure.INVALID_INPUT)
        if (photoUris.any { it !in known }) return ProcessResult.Failed(ProcessFailure.SOURCE_MISSING)
        lastTimelapse = photoUris
        return ProcessResult.Saved("content://fake/video/${++n}")
    }
}
