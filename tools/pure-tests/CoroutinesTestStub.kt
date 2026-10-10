// Nur fuer tools/run-core-tests.sh: Ersatz fuer kotlinx-coroutines-test mit echter Zeit. Gradle kompiliert diese Datei nie.
package kotlinx.coroutines.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
fun runTest(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }
suspend fun CoroutineScope.advanceTimeBy(ms: Long) = delay(ms)
