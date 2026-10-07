package app.cayresim.shell

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cayresim.core.designsystem.CayResimTheme
import dagger.hilt.android.AndroidEntryPoint

/** Shell (R10): baut nichts selbst, ruft nur den obersten Screen auf. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val shutterKeys = ShutterKeys()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        shutterKeys.handle(event.keyCode, event.action, event.repeatCount) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CayResimTheme { AppRoot() } }
    }
}
