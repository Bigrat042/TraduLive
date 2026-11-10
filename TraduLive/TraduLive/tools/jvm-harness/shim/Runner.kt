import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var pass = 0; var fail = 0
    val failures = ArrayList<String>()
    for (cn in args) {
        val cls = Class.forName(cn)
        val methods = cls.declaredMethods.filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" } }.sortedBy { it.name }
        for (m in methods) {
            val t0 = System.nanoTime()
            try {
                val inst = cls.getDeclaredConstructor().newInstance()
                m.invoke(inst)
                pass++; println("PASS  ${cls.simpleName}.${m.name}  (${(System.nanoTime() - t0) / 1_000_000} ms)")
            } catch (e: InvocationTargetException) {
                fail++; val msg = "${cls.simpleName}.${m.name}: ${e.targetException}"; failures.add(msg); println("FAIL  $msg")
            }
        }
    }
    println("----\nRÉSULTAT : $pass réussis, $fail échoués")
    System.exit(if (fail == 0) 0 else 1)
}
