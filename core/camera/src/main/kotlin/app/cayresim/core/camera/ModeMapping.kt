package app.cayresim.core.camera

import androidx.camera.extensions.ExtensionMode
import app.cayresim.core.boundary.PhotoMode
import app.cayresim.core.entity.ModeKey

/** Uebersetzung zwischen Boundary, Entity und CameraX. Nur hier (R11). */
internal fun PhotoMode.toKey(): ModeKey = ModeKey.valueOf(name)
internal fun ModeKey.toPhotoMode(): PhotoMode = PhotoMode.valueOf(name)

internal fun ModeKey.toExtensionMode(): Int = when (this) {
    ModeKey.NORMAL -> ExtensionMode.NONE
    ModeKey.AUTO -> ExtensionMode.AUTO
    ModeKey.NIGHT -> ExtensionMode.NIGHT
    ModeKey.HDR -> ExtensionMode.HDR
    ModeKey.BOKEH -> ExtensionMode.BOKEH
    ModeKey.FACE_RETOUCH -> ExtensionMode.FACE_RETOUCH
}
