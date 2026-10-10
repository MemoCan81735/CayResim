// Nur fuer tools/run-core-tests.sh: Ersatz fuer kotlinx-coroutines-test mit echter Zeit. Gradle kompiliert diese Datei nie.
package kotlinx.coroutines.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
/** Wie der Scheduler von runTest, aber mit echter Uhr (Millisekunden). */
class StubScheduler { val currentTime: Long get() = System.nanoTime() / 1_000_000 }
/** Wie TestScope: Tests lesen testScheduler.currentTime ohne eigenen Import. */
class TestScope(scope: CoroutineScope) : CoroutineScope by scope { val testScheduler = StubScheduler() }
fun runTest(block: suspend TestScope.() -> Unit) { runBlocking { TestScope(this).block() } }
suspend fun CoroutineScope.advanceTimeBy(ms: Long) = delay(ms)
