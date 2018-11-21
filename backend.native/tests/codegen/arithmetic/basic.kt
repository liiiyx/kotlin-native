package codegen.arithmetic.basic

import kotlin.test.*

// Check that compiler doesn't optimize it to `true`
fun selfCmp1(x: Int) = x + 1 > x

fun selfCmp2(x: Int) = x - 1 < x

@Test
fun runTest() {
    assertEquals(1, 0f.compareTo(-0f))
    assertEquals(1, 0.0.compareTo(-0.0))

    assertEquals(-2147483648, 1 shl -1)
    assertEquals(0, 1 shr -1)
    assertEquals(1, 1 shl 32)
    assertEquals(1073741823, -1 ushr 2)
    assertEquals(-1, -1 shr 2)

    assertEquals(1.0, Double.fromBits(1.0.toBits()))
    assertEquals(1.0f, Float.fromBits(1.0f.toBits()))

    assertEquals(Double.NaN, Double.fromBits((0 / 0.0).toBits()))
    assertEquals(Float.NaN, Float.fromBits((0 / 0f).toBits()))

    assertFalse(selfCmp1(Int.MAX_VALUE))
    assertFalse(selfCmp2(Int.MIN_VALUE))

    assertFailsWith(ArithmeticException::class, { 5 / 0 })
    assertEquals(1, 5 / try { 0 / 0; 1 } catch (e: ArithmeticException) { 5 })
    assertEquals(Double.NaN, 0.0 / 0.0)
}