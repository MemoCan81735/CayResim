package app.cayresim.shell

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.Executors

@HiltAndroidApp
class CayResimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) installStrictMode()
    }

    private fun isDebuggable() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * R18: Speicher- oder Netzzugriff auf dem Main-Thread aus eigenem Code beendet die Debug-App.
     * Verstoesse aus Bibliotheken werden nur protokolliert, damit fremder Code die Tests nicht kippt.
     */
    private fun installStrictMode() {
        val main = Handler(Looper.getMainLooper())
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyLog()
                .penaltyListener(Executors.newSingleThreadExecutor()) { v ->
                    if (v.stackTrace.any { it.className.startsWith("app.cayresim.") && !it.className.startsWith("app.cayresim.shell.CayResimApp") }) {
                        main.post { throw IllegalStateException("StrictMode-Verstoss in eigenem Code (R18)", v) }
                    }
                }.build(),
        )
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
    }
}
