package app.cayresim.shell

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShutterKeysTest {
    private var shots = 0
    private val keys = ShutterKeys().apply { listener = { shots++ } }

    @Test fun `Guter Fall beide Lautstaerketasten loesen einmal aus`() {
        assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0))
        assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertEquals(2, shots)
    }

    @Test fun `Randfall Gedrueckthalten loest nicht dauernd aus`() {
        keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0)
        repeat(10) { assertTrue(keys.handle(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, it + 1)) }
        assertEquals(1, shots)
    }

    @Test fun `Andere Tasten bleiben unberuehrt`() {
        assertFalse(keys.handle(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0))
        assertEquals(0, shots)
    }

    @Test fun `Ohne Kamera-Screen regeln die Tasten die Lautstaerke`() {
        keys.listener = null
        assertFalse(keys.handle(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertEquals(0, shots)
    }
}
