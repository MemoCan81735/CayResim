package app.cayresim.core.camera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Eigener Lebenszyklus der Kamera: start() und stop() der Boundary steuern ihn (R15).
 * Die Composable ruft start/stop ueber das ViewModel lebenszyklusbewusst auf.
 * Nur auf dem Main-Thread benutzen.
 */
internal class AdapterLifecycleOwner : LifecycleOwner {
    private var registry: LifecycleRegistry? = null

    override val lifecycle: Lifecycle
        get() = registry ?: LifecycleRegistry(this).also { it.currentState = Lifecycle.State.CREATED; registry = it }

    fun resume() { (lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED }
    fun pause() { (lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED }
    val isActive: Boolean get() = registry?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
}
