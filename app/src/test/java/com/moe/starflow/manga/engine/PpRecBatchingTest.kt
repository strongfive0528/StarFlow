package com.moe.starflow.manga.engine

import com.moe.starflow.manga.types.RecResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PpRecBatchingTest {
    private fun output(indices: List<Int>, seq: Int = 209, classes: Int = 18385) =
        PpRecBatching.BatchOutput(seq, classes, indices.map { RecResult("line-$it", it / 100f) })

    @Test fun budgetUsesAvailableHeapAndHas32MiBCeiling() {
        assertEquals(32L * 1024 * 1024, PpRecBatching.budget(256L * 1024 * 1024, 80L * 1024 * 1024, 24L * 1024 * 1024))
        assertEquals(4L * 1024 * 1024, PpRecBatching.budget(32L * 1024 * 1024, 24L * 1024 * 1024, 8L * 1024 * 1024))
        assertEquals(0L, PpRecBatching.budget(0, 1, 0))
    }

    @Test fun normalizationPadsInsteadOfSquashingLongText() {
        assertEquals(1696, PpRecBatching.normalizedWidth(1672, 48))
        assertEquals(32, PpRecBatching.normalizedWidth(1, 200))
        assertEquals(96, PpRecBatching.normalizedWidth(100, 60))
    }

    @Test fun logged234MiBOutputIsSplitBeforeCopying() {
        val calls = mutableListOf<Int>()
        val results = PpRecBatching.recognize(List(21) { 1696 }, 16, { PpRecBatching.MAX_BUDGET_BYTES }) { ids, width, budget ->
            calls += ids.size
            val cost = PpRecBatching.workingBytes(width, ids.size, 209, 18385)
            assertTrue("bounded before ORT output copy", cost <= budget)
            output(ids)
        }
        assertTrue(calls.all { it == 1 })
        assertEquals((0..20).map { "line-$it" }, results.map { it.text })
    }

    @Test fun shorterAndSmallerDictionaryOutputsCanStillBatch() {
        for (classes in listOf(100, 18710)) {
            val calls = mutableListOf<Int>()
            PpRecBatching.recognize(List(12) { 320 }, 6, { PpRecBatching.MAX_BUDGET_BYTES }) { ids, _, _ ->
                calls += ids.size
                output(ids, 40, classes)
            }
            assertEquals(1, calls.first())
            assertEquals(if (classes == 100) 6 else 5, calls.maxOrNull())
            assertTrue(calls.all { it <= 6 })
        }
    }

    @Test fun widthSortingRestoresTextsAndScoresToOriginalIndices() {
        val widths = listOf(320, 64, 320, 128, 64)
        val calls = mutableListOf<Pair<Int, List<Int>>>()
        val results = PpRecBatching.recognize(widths, 16, { PpRecBatching.MAX_BUDGET_BYTES }) { ids, width, _ ->
            calls += width to ids.toList()
            assertTrue(ids.all { widths[it] == width })
            output(ids, 10, 100)
        }
        assertEquals(64, calls.first().first)
        assertEquals(widths.indices.map { "line-$it" }, results.map { it.text })
        assertEquals(widths.indices.map { it / 100f }, results.map { it.score })
    }

    @Test fun oversizedSingleOutputReportsRecoverableFailure() {
        var calls = 0
        val error = assertThrows(OcrMemoryException::class.java) {
            PpRecBatching.recognize(listOf(4096, 4096), 16, { PpRecBatching.MAX_BUDGET_BYTES }) { ids, _, _ ->
                calls++
                output(ids, 1024)
            }
        }
        assertEquals(1, calls)
        assertTrue(error.diagnostic.contains("single"))
    }

    @Test fun completedSingleProbeKeepsItsBudgetSnapshot() {
        var reads = 0
        val results = PpRecBatching.recognize(listOf(320), 6, {
            if (reads++ == 0) PpRecBatching.MAX_BUDGET_BYTES else 0L
        }) { ids, _, _ -> output(ids, 40, 100) }
        assertEquals(1, results.size)
        assertEquals(1, reads)
    }

    @Test fun allocationFailureShrinksBatchButDoesNotLoopOnSingleFailure() {
        val calls = mutableListOf<Int>()
        val results = PpRecBatching.recognize(List(8) { 320 }, 6, { PpRecBatching.MAX_BUDGET_BYTES }) { ids, _, _ ->
            calls += ids.size
            if (ids.size > 2) throw OutOfMemoryError("simulated")
            output(ids, 10, 100)
        }
        assertEquals(8, results.size)
        assertTrue(calls.containsAll(listOf(6, 3, 1)))
        val original = OutOfMemoryError("single")
        val error = assertThrows(OcrMemoryException::class.java) {
            PpRecBatching.recognize(listOf(320), 6) { _, _, _ -> throw original }
        }
        assertSame(original, error.cause)
    }

    @Test fun cancellationPropagatesWithoutRetry() {
        val cancelled = CancellationException("stop")
        var calls = 0
        val actual = assertThrows(CancellationException::class.java) {
            PpRecBatching.recognize(listOf(320, 320), 6) { _, _, _ -> calls++; throw cancelled }
        }
        assertSame(cancelled, actual)
        assertEquals(1, calls)
    }

    @Test fun inputAndTensorOverflowAreRejectedBeforeAllocation() {
        assertThrows(OcrMemoryException::class.java) { PpRecBatching.requireInputFits(Int.MAX_VALUE, 16, 1024) }
        assertThrows(OcrMemoryException::class.java) {
            PpRecBatching.workingBytes(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }
}
