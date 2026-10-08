package app.cayresim.shell

import android.view.KeyEvent

/**
 * Lautstaerketasten als Ausloeser. Die Activity faengt die Tasten ab, bevor der Sucher sie
 * schlucken kann (der Sucher haelt den Tastaturfokus), und gibt sie an den Kamera-Screen weiter.
 * Ohne angemeldeten Empfaenger (z. B. in der Galerie) regeln die Tasten wie gewohnt die Lautstaerke.
 */
class ShutterKeys {
    /** Empfaenger; liefert true, wenn er die Taste nutzt (Kamera laeuft), sonst regelt sie die Lautstaerke. */
    var listener: (() -> Boolean)? = null
    private var consuming = false

    /** true = Taste verbraucht. Ausgeloest wird nur beim ersten Druck, nicht beim Gedrueckthalten. */
    fun handle(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val l = listener ?: return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) consuming = l()
        // Wiederholungen und Loslassen folgen der Entscheidung beim ersten Druck
        return consuming
    }
}
