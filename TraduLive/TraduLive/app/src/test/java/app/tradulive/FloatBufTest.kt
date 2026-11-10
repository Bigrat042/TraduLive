package app.tradulive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatBufTest {
    @Test
    fun growsBeyondInitialCapacity() {
        val b = FloatBuf(4)
        b.append(FloatArray(10) { it.toFloat() })
        assertEquals(10, b.size)
        assertEquals(9f, b.copy()[9], 0f)
    }

    @Test
    fun keepTailKeepsTheEnd() {
        val b = FloatBuf(4)
        b.append(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        b.keepTail(2)
        assertTrue(b.copy().contentEquals(floatArrayOf(4f, 5f)))
    }

    @Test
    fun copyTailDoesNotModifyTheBuffer() {
        val b = FloatBuf(4)
        b.append(floatArrayOf(1f, 2f, 3f))
        assertTrue(b.copyTail(2).contentEquals(floatArrayOf(2f, 3f)))
        assertTrue(b.copyTail(10).contentEquals(floatArrayOf(1f, 2f, 3f)))
        assertEquals(3, b.size)
    }

    @Test
    fun appendCountZeroDoesNothing() {
        val b = FloatBuf(4)
        b.append(floatArrayOf(1f), 0)
        assertEquals(0, b.size)
    }
}
