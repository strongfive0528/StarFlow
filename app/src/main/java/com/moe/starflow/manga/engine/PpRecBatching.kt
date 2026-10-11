package com.moe.starflow.manga.engine

import com.moe.starflow.manga.types.RecResult

/** A recoverable OCR allocation failure; callers must not retry the ordinary OCR route. */
internal class OcrMemoryException(
    val diagnostic: String,
    cause: Throwable? = null,
    message: String = "OCR memory is insufficient. Reduce the selected area and try again.",
) : Exception(message, cause)

/** Shared PP recognition limits. The estimate includes native output and its Java copy. */
internal object PpRecBatching {
    const val HEIGHT = 48
    const val MAX_BUDGET_BYTES = 32L * 1024 * 1024

    data class BatchOutput(val seqLen: Int, val numClasses: Int, val results: List<RecResult>)

    fun budget(maxHeap: Long, allocatedHeap: Long, freeHeap: Long): Long =
        minOf(MAX_BUDGET_BYTES, (maxHeap - allocatedHeap + freeHeap).coerceAtLeast(0L) / 4)

    fun currentBudget(): Long = Runtime.getRuntime().let {
        budget(it.maxMemory(), it.totalMemory(), it.freeMemory())
    }

    fun normalizedWidth(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val natural = (width.toLong() * HEIGHT + height - 1) / height
        // Padding, not stretching: equal widths can share an observed output shape.
        val padded = ((natural + 31) / 32) * 32
        if (padded > Int.MAX_VALUE) throw OcrMemoryException("normalized width=$padded")
        return padded.toInt().coerceAtLeast(32)
    }

    fun inputBytes(width: Int, batch: Int): Long = checked {
        Math.multiplyExact(Math.multiplyExact(width.toLong(), batch.toLong()), 3L * HEIGHT * 4)
    }

    private fun scratchBytes(width: Int): Long = width.toLong() * HEIGHT * 8

    fun workingBytes(width: Int, batch: Int, seqLen: Int, numClasses: Int): Long = checked {
        require(width > 0 && batch > 0 && seqLen > 0 && numClasses > 0)
        val output = Math.multiplyExact(
            Math.multiplyExact(batch.toLong(), seqLen.toLong()), numClasses.toLong() * 4
        )
        Math.addExact(Math.addExact(inputBytes(width, batch), Math.multiplyExact(output, 2)), scratchBytes(width))
    }

    fun requireInputFits(width: Int, batch: Int, budget: Long) {
        val bytes = checked { Math.addExact(inputBytes(width, batch), scratchBytes(width)) }
        requireFits(bytes, budget, "input width=$width batch=$batch")
    }

    fun requireFits(bytes: Long, budget: Long, diagnostic: String) {
        if (bytes > budget) throw OcrMemoryException("$diagnostic workingBytes=$bytes budget=$budget")
    }

    private fun checked(block: () -> Long): Long = try {
        block()
    } catch (e: ArithmeticException) {
        throw OcrMemoryException("recognition tensor size overflow", e)
    }

    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw OcrMemoryException("recognition allocation failed", e)
    }

    /** Probe each width with one real crop, then batch using the actual model output shape. */
    fun recognize(
        widths: List<Int>,
        maxBatch: Int,
        budget: () -> Long = ::currentBudget,
        runBatch: (indices: List<Int>, width: Int, budgetBytes: Long) -> BatchOutput,
    ): List<RecResult> {
        if (widths.isEmpty()) return emptyList()
        require(maxBatch > 0 && widths.all { it > 0 })
        val restored = arrayOfNulls<RecResult>(widths.size)
        for ((width, indices) in widths.indices.groupBy { widths[it] }.toSortedMap()) {
            val firstBudget = budget()
            val first = guard { runBatch(listOf(indices.first()), width, firstBudget) }
            require(first.results.size == 1 && first.seqLen > 0 && first.numClasses > 0)
            requireFits(workingBytes(width, 1, first.seqLen, first.numClasses), firstBudget, "single width=$width")
            restored[indices.first()] = first.results.single()
            var cursor = 1
            var groupMaxBatch = maxBatch
            while (cursor < indices.size) {
                val limit = budget()
                var count = minOf(groupMaxBatch, indices.size - cursor)
                while (count > 1 && workingBytes(width, count, first.seqLen, first.numClasses) > limit) count--
                requireFits(workingBytes(width, count, first.seqLen, first.numClasses), limit, "width=$width batch=$count")
                while (true) {
                    val batchIndices = indices.subList(cursor, cursor + count)
                    try {
                        val output = guard { runBatch(batchIndices, width, limit) }
                        require(output.results.size == count)
                        batchIndices.forEachIndexed { i, index -> restored[index] = output.results[i] }
                        cursor += count
                        break
                    } catch (e: OcrMemoryException) {
                        if (count == 1) throw e
                        count = (count / 2).coerceAtLeast(1)
                        groupMaxBatch = count
                    }
                }
            }
        }
        return restored.map { requireNotNull(it) }
    }
}
