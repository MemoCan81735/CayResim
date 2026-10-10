package app.cayresim.feature.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** S-014: Bilder der Schwenk-Anleitung, von der App gezeichnet (keine Bilddateien, keine fremden Grafiken). */
enum class SweepPicture { START, TAP, YAW, ROLL, PITCH, CIRCLE }

/**
 * Ein Bild im Seitenverhaeltnis 160 x 110 (Entwurf vom 10.10.). Handy als Rechteck mit Rand, Bewegung als Pfeil in der
 * Akzentfarbe. Feste Bilder ohne Animation, damit Screenshots gleich bleiben.
 */
@Composable
fun SweepPictogram(picture: SweepPicture, width: Dp, modifier: Modifier = Modifier) {
    val phone = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(width, width * 110f / 160f)) {
        val k = size.width / 160f
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun phoneAt(x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
            drawRoundRect(phone.copy(alpha = alpha), o(x, y), Size(w * k, h * k), CornerRadius(7 * k))
            drawRoundRect(line.copy(alpha = alpha), o(x, y), Size(w * k, h * k), CornerRadius(7 * k), style = Stroke(2.5f * k))
        }
        val arrow = Stroke(3f * k, cap = StrokeCap.Round)
        fun head(tip: Offset, a: Offset, b: Offset) = drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }, accent)
        when (picture) {
            SweepPicture.START -> {
                phoneAt(20f, 38f, 70f, 36f)
                drawCircle(line, 4 * k, o(80f, 56f))
                drawRoundRect(muted, o(122f, 40f), Size(22 * k, 32 * k), CornerRadius(4 * k), style = Stroke(2f * k))
                drawCircle(muted, 6 * k, o(133f, 62f), style = Stroke(2f * k))
                drawLine(accent, o(94f, 56f), o(112f, 56f), 3f * k, StrokeCap.Round)
                head(o(118f, 56f), o(110f, 51f), o(110f, 61f))
            }
            SweepPicture.TAP -> {
                phoneAt(45f, 38f, 70f, 36f)
                drawCircle(accent, 10 * k, o(80f, 56f), style = Stroke(3f * k))
                drawCircle(accent.copy(alpha = 0.5f), 18 * k, o(80f, 56f), style = Stroke(3f * k))
            }
            SweepPicture.YAW -> {
                phoneAt(45f, 34f, 70f, 36f)
                drawPath(Path().apply { moveTo(40 * k, 88 * k); quadraticTo(80 * k, 104 * k, 120 * k, 88 * k) }, accent, style = arrow)
                head(o(125.6f, 85.8f), o(118.2f, 94.1f), o(114.4f, 84.9f)); head(o(34.4f, 85.8f), o(45.6f, 84.9f), o(41.8f, 94.1f))
            }
            SweepPicture.ROLL -> {
                phoneAt(28f, 46f, 56f, 30f, alpha = 0.45f)
                phoneAt(104f, 26f, 30f, 56f)
                drawPath(Path().apply { moveTo(66 * k, 36 * k); quadraticTo(88 * k, 14 * k, 104 * k, 22 * k) }, accent, style = arrow)
                head(o(109.4f, 24.7f), o(98.2f, 24.7f), o(102.6f, 15.7f))
            }
            SweepPicture.PITCH -> {
                phoneAt(40f, 38f, 70f, 36f)
                // Bogen rechts neben dem Handy, Pfeile an beiden Enden (oben und unten)
                drawArc(accent, -60f, 120f, false, o(112f, 26f), Size(28 * k, 60 * k), style = arrow)
                head(o(129.2f, 25.3f), o(139.4f, 30f), o(131.6f, 36.3f)); head(o(129.2f, 86.7f), o(139.4f, 82.1f), o(131.6f, 75.8f))
            }
            SweepPicture.CIRCLE -> {
                phoneAt(55f, 42f, 50f, 28f)
                drawArc(accent, -80f, 300f, false, o(44f, 20f), Size(72 * k, 72 * k), style = arrow)
                head(o(56.2f, 28.3f), o(53.7f, 39.2f), o(45.9f, 32.8f))
            }
        }
    }
}
