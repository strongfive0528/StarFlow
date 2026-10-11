package com.moe.starflow.manga.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

class PpRecCtcDecoderTest {
    private val dict = listOf("", "あ", "い", " ")

    @Test fun directBufferOffsetsBlankAndDuplicatesPreserveConfidence() {
        val indices = listOf(1, 1, 0, 1, 2, 2, 0, 0, 2, 2, 0, 1, 0, 0)
        val buffer = ByteBuffer.allocateDirect(indices.size * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        indices.forEachIndexed { t, chosen -> repeat(4) { c -> buffer.put(if (c == chosen) (t + 1) / 20f else 0f) } }
        buffer.flip()
        buffer.position(3)
        val first = PpRecCtcDecoder.decode(buffer, 0, 7, 4, dict)
        val second = PpRecCtcDecoder.decode(buffer, 1, 7, 4, dict)
        assertEquals("ああい", first.text)
        assertEquals((0.05f + 0.2f + 0.25f) / 3, first.score, 0.00001f)
        assertEquals("いあ", second.text)
        assertEquals((0.45f + 0.6f) / 2, second.score, 0.00001f)
        assertEquals(3, buffer.position())
    }

    @Test fun blankAndSpaceOnlyResultsStayEmpty() {
        for (chosen in listOf(0, 3)) {
            val data = FloatArray(8) { if (it % 4 == chosen) 1f else 0f }
            val result = PpRecCtcDecoder.decode(FloatBuffer.wrap(data), 0, 2, 4, dict)
            assertEquals("", result.text)
            assertEquals(0f, result.score, 0f)
        }
    }

    @Test fun missingDictionaryEntryIsSkippedAndInvalidOffsetIsRejected() {
        val buffer = FloatBuffer.wrap(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f))
        assertEquals("い", PpRecCtcDecoder.decode(buffer, 0, 2, 4, dict.take(3)).text)
        assertThrows(IllegalArgumentException::class.java) { PpRecCtcDecoder.decode(buffer, 1, 2, 4, dict) }
    }
}
