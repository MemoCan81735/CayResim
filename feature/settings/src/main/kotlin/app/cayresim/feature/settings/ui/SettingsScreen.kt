package app.cayresim.feature.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cayresim.feature.settings.R

/** Einstieg hinter dem Zahnrad: Anleitung und Selbsttest. Reine Anzeige, kein Zustand. */
@Composable
fun SettingsContent(onGuide: () -> Unit, onSelfTest: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Entry(R.string.settings_guide, R.string.settings_guide_hint, onGuide, "settings_guide")
            HorizontalDivider()
            Entry(R.string.selftest_title, R.string.settings_selftest_hint, onSelfTest, "settings_selftest")
        }
    }
}

@Composable
private fun Entry(@StringRes title: Int, @StringRes hint: Int, onClick: () -> Unit, tag: String) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag(tag).semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Abschnitte der Anleitung in Lesereihenfolge; Tests pruefen, dass jeder angezeigt wird. */
internal val guideSections: List<Pair<Int, Int>> = listOf(
    R.string.guide_shoot_title to R.string.guide_shoot_body,
    R.string.guide_zoom_title to R.string.guide_zoom_body,
    R.string.guide_modes_title to R.string.guide_modes_body,
    R.string.guide_look_title to R.string.guide_look_body,
    R.string.guide_special_title to R.string.guide_special_body,
    R.string.guide_series_title to R.string.guide_series_body,
    R.string.guide_gallery_title to R.string.guide_gallery_body,
    R.string.guide_selftest_title to R.string.guide_selftest_body,
)

/** Anleitung fuer Einsteiger. Reine Anzeige aus Textressourcen. */
@Composable
fun GuideContent(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp).testTag("guide"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp).testTag("back")) { Text(stringResource(R.string.back)) } }
            item { Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineMedium) }
            item { Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyLarge) }
            items(guideSections) { (title, body) ->
                Column(Modifier.testTag("guide_$title").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }
}
