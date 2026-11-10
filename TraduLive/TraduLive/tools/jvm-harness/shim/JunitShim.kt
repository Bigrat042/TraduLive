// Remplace JUnit 4 UNIQUEMENT dans mon environnement (Maven est bloqué). Mêmes noms, mêmes surcharges.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

object Assert {
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("attendu <$expected> mais obtenu <$actual>") }
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("$message : attendu <$expected> mais obtenu <$actual>") }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) { if (expected != actual) throw AssertionError("attendu <$expected> mais obtenu <$actual>") }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) { if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("attendu <$expected> mais obtenu <$actual>") }
    @JvmStatic fun assertTrue(cond: Boolean) { if (!cond) throw AssertionError("condition fausse") }
    @JvmStatic fun assertTrue(message: String, cond: Boolean) { if (!cond) throw AssertionError(message) }
    @JvmStatic fun assertFalse(cond: Boolean) { if (cond) throw AssertionError("condition vraie") }
    @JvmStatic fun assertFalse(message: String, cond: Boolean) { if (cond) throw AssertionError(message) }
    @JvmStatic fun assertNull(o: Any?) { if (o != null) throw AssertionError("attendu null mais obtenu <$o>") }
    @JvmStatic fun assertNotNull(o: Any?) { if (o == null) throw AssertionError("attendu non null") }
}
