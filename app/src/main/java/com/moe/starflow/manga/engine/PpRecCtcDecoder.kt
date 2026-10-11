package com.moe.starflow.manga.engine

import com.moe.starflow.manga.types.RecResult
import java.nio.FloatBuffer

/** CTC decoding directly from one sample's offset in the shared output copy. */
internal object PpRecCtcDecoder {
    fun decode(buffer: FloatBuffer, sample: Int, seqLen: Int, numClasses: Int, dict: List<String>): RecResult {
        val start = sample.toLong() * seqLen * numClasses
        require(sample >= 0 && seqLen > 0 && numClasses > 0)
        require(start + seqLen.toLong() * numClasses <= buffer.limit())
        val text = StringBuilder()
        var totalScore = 0f
        var count = 0
        var previous = -1
        for (t in 0 until seqLen) {
            val offset = (start + t.toLong() * numClasses).toInt()
            var best = 0
            var probability = buffer.get(offset)
            for (c in 1 until numClasses) {
                val value = buffer.get(offset + c)
                if (value > probability) {
                    best = c
                    probability = value
                }
            }
            if (best > 0 && best != previous && best < dict.size) {
                text.append(dict[best])
                totalScore += probability
                count++
            }
            previous = best // blank resets the duplicate check, just as in the original decoder.
        }
        return if (text.isBlank()) RecResult("", 0f)
        else RecResult(text.toString(), if (count > 0) totalScore / count else 0f)
    }
}
