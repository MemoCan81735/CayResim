package app.cayresim.feature.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.cayresim.core.pure.HoldGuide
import app.cayresim.feature.camera.R
import app.cayresim.feature.camera.control.HoldUi

/** Gruen im Kreis, orange ausserhalb (S-016). */
private val HOLD_STEADY = Color(0xFF4CAF50)
private val HOLD_OFF = Color(0xFFFF9800)

/**
 * S-016: Halte-Kreis wie beim S24+. Der Punkt zeigt, wohin die Kamera gegenueber dem Start zeigt; bei
 * [HoldGuide.RADIUS_DEG] liegt er auf dem Kreis, hoechstens beim Dreifachen. Der Ring zeigt den Fortschritt.
 * [displayRotation] in Grad; ohne Angabe die Drehung der Anzeige des Geraets.
 */
@Composable
fun HoldCircle(hold: HoldUi, modifier: Modifier = Modifier, displayRotation: Int? = null) {
    val rotation = displayRotation ?: ((LocalView.current.display?.rotation ?: 0) * 90)
    val (rightDeg, downDeg) = HoldGuide.toScreen(HoldGuide.Deviation(hold.rightDeg, hold.upDeg, hold.steady), rotation)
    val state = stringResource(if (hold.steady) R.string.hold_inside else R.string.hold_outside)
    val ring = MaterialTheme.colorScheme.primary
    Column(modifier.testTag("hold_circle").semantics { stateDescription = state }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(132.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = 32.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), r, c)
            drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
            // Fortschritt als Ring um den Kreis, oben beginnend im Uhrzeigersinn
            val rr = 44.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.25f), rr, c, style = Stroke(3.dp.toPx()))
            drawArc(ring, -90f, 360f * hold.progress, false, Offset(c.x - rr, c.y - rr), Size(2 * rr, 2 * rr),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            val scale = r / HoldGuide.RADIUS_DEG.toFloat()
            fun clamp(v: Float) = v.coerceIn(-3 * r, 3 * r).coerceIn(-size.width / 2 + 8.dp.toPx(), size.width / 2 - 8.dp.toPx())
            val dot = Offset(c.x + clamp(rightDeg.toFloat() * scale), c.y + clamp(downDeg.toFloat() * scale))
            drawCircle(if (hold.steady) HOLD_STEADY else HOLD_OFF, 7.dp.toPx(), dot)
            drawCircle(Color.White, 7.dp.toPx(), dot, style = Stroke(1.5f.dp.toPx()))
        }
        Text(stringResource(R.string.hold_still), color = Color.White,
            style = MaterialTheme.typography.titleSmall.merge(TextStyle(shadow = Shadow(Color.Black, Offset(1f, 1f), 4f))))
    }
}
