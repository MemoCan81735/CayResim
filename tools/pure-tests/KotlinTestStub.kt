// Ersatz fuer kotlin.test, nur fuer tools/run-pure-tests.sh (lokaler Lauf ohne Maven). Gradle kompiliert diese Datei nie.
package kotlin.test
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Test
annotation class BeforeTest
annotation class AfterTest
class AssertionFailed(m: String): AssertionError(m)
fun assertTrue(v: Boolean, message: String? = null) { if (!v) throw AssertionFailed(message ?: "expected true") }
fun assertTrue(message: String? = null, block: () -> Boolean) = assertTrue(block(), message)
fun assertFalse(v: Boolean, message: String? = null) { if (v) throw AssertionFailed(message ?: "expected false") }
fun <T> assertEquals(expected: T, actual: T, message: String? = null) { if (expected != actual) throw AssertionFailed("${message ?: ""} expected <$expected> actual <$actual>") }
fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double, message: String? = null) { if (kotlin.math.abs(expected - actual) > absoluteTolerance) throw AssertionFailed("${message ?: ""} expected <$expected> actual <$actual>") }
fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String? = null) { if (kotlin.math.abs(expected - actual) > absoluteTolerance) throw AssertionFailed("${message ?: ""} expected <$expected> actual <$actual>") }
fun assertContentEquals(expected: ByteArray?, actual: ByteArray?, message: String? = null) { if (!expected.contentEquals(actual)) throw AssertionFailed(message ?: "content differs") }
fun assertContentEquals(expected: IntArray?, actual: IntArray?, message: String? = null) { if (!expected.contentEquals(actual)) throw AssertionFailed(message ?: "content differs") }
fun assertContentEquals(expected: FloatArray?, actual: FloatArray?, message: String? = null) { if (!expected.contentEquals(actual)) throw AssertionFailed(message ?: "content differs") }
fun <T> assertContentEquals(expected: List<T>?, actual: List<T>?, message: String? = null) { if (expected != actual) throw AssertionFailed(message ?: "content differs") }
fun <T : Any> assertNotNull(actual: T?, message: String? = null): T = actual ?: throw AssertionFailed(message ?: "expected not null")
fun assertNull(actual: Any?, message: String? = null) { if (actual != null) throw AssertionFailed("${message ?: ""} expected null actual <$actual>") }
inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    try { block() } catch (e: Throwable) { if (e is T) return e; throw AssertionFailed("wrong exception $e") }
    throw AssertionFailed(message ?: "no exception")
}
