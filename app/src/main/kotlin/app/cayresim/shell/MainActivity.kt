package app.cayresim.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cayresim.core.designsystem.CayResimTheme
import dagger.hilt.android.AndroidEntryPoint

/** Shell (R10): baut nichts selbst, ruft nur den obersten Screen auf. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CayResimTheme { AppRoot() } }
    }
}
