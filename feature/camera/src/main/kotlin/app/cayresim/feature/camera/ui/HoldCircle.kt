package app.cayresim.feature.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.cayresim.core.pure.HoldGuide
import app.cayresim.feature.camera.R
import app.cayresim.feature.camera.control.HoldUi
import kotlin.math.hypot

/** Gruen im Kreis, orange ausserhalb (S-016). */
private val HOLD_STEADY = Color(0xFF4CAF50)
private val HOLD_OFF = Color(0xFFFF9800)

/**
 * S-016: Halte-Kreis wie beim S24+. Der Punkt zeigt, wohin die Kamera gegenueber dem Start zeigt; bei
 * [HoldGuide.RADIUS_DEG] liegt er auf dem Kreis, hoechstens beim Dreifachen (radial begrenzt). Der Ring zeigt den
 * Fortschritt. Kein eigener Text: der Hinweis "Serie laeuft, Handy ruhig halten" steht schon unten
 * (Zweitpruefung S-016, H5 und H6). [displayRotation] in Grad; ohne Angabe die Drehung der Anzeige des Geraets.
 */
@Composable
fun HoldCircle(hold: HoldUi, modifier: Modifier = Modifier, displayRotation: Int? = null) {
    val rotation = displayRotation ?: ((LocalView.current.display?.rotation ?: 0) * 90)
    val (rightDeg, downDeg) = HoldGuide.toScreen(HoldGuide.Deviation(hold.rightDeg, hold.upDeg, hold.steady), rotation)
    val state = stringResource(if (hold.steady) R.string.hold_inside else R.string.hold_outside)
    val label = stringResource(R.string.hold_still)
    val ring = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(200.dp).testTag("hold_circle").semantics(mergeDescendants = true) { contentDescription = label; stateDescription = state }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = 30.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), r, c)
        drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
        // Fortschritt als Ring um den Kreis, oben beginnend im Uhrzeigersinn
        val rr = 40.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.25f), rr, c, style = Stroke(3.dp.toPx()))
        drawArc(ring, -90f, 360f * hold.progress, false, Offset(c.x - rr, c.y - rr), Size(2 * rr, 2 * rr),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        // Punkt: 0,5 Grad auf dem Kreis, radial hoechstens beim Dreifachen
        val scale = r / HoldGuide.RADIUS_DEG.toFloat()
        var dx = rightDeg.toFloat() * scale; var dy = downDeg.toFloat() * scale
        val len = hypot(dx, dy); val max = 3 * r
        if (len > max) { dx *= max / len; dy *= max / len }
        val dot = Offset(c.x + dx, c.y + dy)
        drawCircle(if (hold.steady) HOLD_STEADY else HOLD_OFF, 7.dp.toPx(), dot)
        drawCircle(Color.White, 7.dp.toPx(), dot, style = Stroke(1.5f.dp.toPx()))
    }
}
