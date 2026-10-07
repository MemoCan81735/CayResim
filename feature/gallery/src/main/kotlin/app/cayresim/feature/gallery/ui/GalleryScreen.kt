package app.cayresim.feature.gallery.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cayresim.feature.gallery.R
import app.cayresim.feature.gallery.control.GalleryUiState
import app.cayresim.feature.gallery.control.GalleryViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryRoute(onBack: () -> Unit, viewModel: GalleryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    GalleryContent(state, onBack) { uri ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }
}

@Composable
fun GalleryContent(state: GalleryUiState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.headlineMedium)
            if (!state.loading && state.photos.isEmpty()) Text(stringResource(R.string.gallery_empty), Modifier.testTag("empty"))
            LazyColumn {
                items(state.photos, key = { it.uri }) { p ->
                    Text(format.format(Date(p.takenAtMillis)),
                        Modifier.fillMaxWidth().clickable { onOpen(p.uri) }.padding(vertical = 12.dp).testTag("photo"))
                }
            }
        }
    }
}
