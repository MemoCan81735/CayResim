package app.cayresim.core.data

import app.cayresim.core.boundary.DebugOptionsBoundary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Debug-Schalter nur im Speicher (S-011): nach jedem Neustart aus, nichts wird gespeichert. */
@Singleton
class InMemoryDebugOptionsAdapter @Inject constructor() : DebugOptionsBoundary {
    private val nightSeries = MutableStateFlow(false)
    override val saveNightSeries: StateFlow<Boolean> = nightSeries.asStateFlow()
    override fun setSaveNightSeries(on: Boolean) { nightSeries.value = on }
}
