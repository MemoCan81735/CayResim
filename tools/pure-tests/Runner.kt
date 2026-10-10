// Fuehrt die mit @Test markierten Methoden der genannten Klassen aus; nur fuer tools/run-pure-tests.sh.
object Runner {
    @JvmStatic fun main(args: Array<String>) {
        var fail = 0; var n = 0
        val filter = System.getenv("FILTER")
        for (cn in args) {
            val c = Class.forName(cn)
            for (m in c.declaredMethods.filter { it.isAnnotationPresent(kotlin.test.Test::class.java) }.sortedBy { it.name }) {
                if (filter != null && !m.name.contains(filter)) continue
                n++
                val inst = c.getDeclaredConstructor().newInstance()
                val t0 = System.nanoTime()
                try { m.isAccessible = true; m.invoke(inst); println("PASS ${c.simpleName} > ${m.name} (${(System.nanoTime() - t0) / 1_000_000} ms)") }
                catch (e: java.lang.reflect.InvocationTargetException) { fail++; println("FAIL ${c.simpleName} > ${m.name}: ${e.targetException}") }
            }
        }
        println("$n tests, $fail failed")
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
