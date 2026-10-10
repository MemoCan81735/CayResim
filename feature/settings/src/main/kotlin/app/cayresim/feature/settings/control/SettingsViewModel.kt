package app.cayresim.feature.settings.control

import androidx.lifecycle.ViewModel
import app.cayresim.core.boundary.DebugOptionsBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Zustand der Einstellungen: nur der Schalter "Nachtserie speichern" (S-011), im Speicher, nach Neustart aus. */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val debug: DebugOptionsBoundary) : ViewModel() {
    val saveNightSeries: StateFlow<Boolean> = debug.saveNightSeries
    fun onSaveNightSeries(on: Boolean) = debug.setSaveNightSeries(on)
}
