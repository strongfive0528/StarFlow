package com.moe.starflow.manga.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.moe.starflow.R
import com.moe.starflow.manga.types.RecResult
import com.moe.starflow.utils.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil

/** Android/ORT adapter for the shared, bounded PPv5/v6 recognition scheduler. */
internal object PpRecRecognizer {
    fun <T> withMemoryGuard(context: Context, tag: String, block: () -> T): T = try {
        PpRecBatching.guard(block)
    } catch (e: OcrMemoryException) {
        LogCollector.e(tag, e.diagnostic, e)
        throw OcrMemoryException(e.diagnostic, e, context.getString(R.string.error_ocr_memory))
    }

    fun recognize(
        env: OrtEnvironment,
        session: OrtSession,
        images: List<Bitmap>,
        dict: List<String>,
        maxBatch: Int,
        tag: String,
    ): List<RecResult> {
        val widths = images.map { PpRecBatching.normalizedWidth(it.width, it.height) }
        var reusableInput: FloatBuffer? = null
        var currentWidth = 0
        return PpRecBatching.recognize(widths, maxBatch) { indices, width, budget ->
            if (width != currentWidth) {
                reusableInput = null
                currentWidth = width
            }
            PpRecBatching.requireInputFits(width, indices.size, budget)
            val bytes = PpRecBatching.inputBytes(width, indices.size)
            if (bytes > Int.MAX_VALUE) throw OcrMemoryException("input buffer bytes=$bytes")
            val elements = (bytes / 4).toInt()
            val buffer = reusableInput?.takeIf { it.capacity() >= elements } ?: ByteBuffer
                .allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .also { reusableInput = it }
            buffer.clear()
            buffer.limit(elements)
            indices.forEach { fillInput(images[it], width, buffer) }
            buffer.flip()
            OnnxTensor.createTensor(env, buffer, longArrayOf(indices.size.toLong(), 3, 48, width.toLong())).use { input ->
                session.run(mapOf("x" to input)).use { result ->
                    val output = session.outputNames.asSequence().mapNotNull { name ->
                        result.get(name).orElse(null) as? OnnxTensor
                    }.firstOrNull() ?: error("PP recognition returned no tensor")
                    val shape = output.info.shape
                    require(shape.size == 3 && shape[0] == indices.size.toLong()) { "Invalid PP output shape: ${shape.contentToString()}" }
                    require(shape[1] in 1..Int.MAX_VALUE.toLong() && shape[2] in 1..Int.MAX_VALUE.toLong())
                    val seqLen = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val working = PpRecBatching.workingBytes(width, indices.size, seqLen, classes) +
                        (buffer.capacity() - elements).toLong() * 4
                    LogCollector.d(tag, "rec batch=${indices.size} width=$width output=${shape.contentToString()} workingBytes=$working budget=$budget")
                    PpRecBatching.requireFits(working, budget, "output=${shape.contentToString()}")
                    // getFloatBuffer copies the ENTIRE tensor in ORT 1.19; check its size first.
                    val predictions = output.floatBuffer ?: error("PP output is not floating point")
                    PpRecBatching.BatchOutput(seqLen, classes, indices.indices.map {
                        PpRecCtcDecoder.decode(predictions, it, seqLen, classes, dict)
                    })
                    // Result owns output; never close the same tensor twice.
                }
            }
        }
    }

    private fun fillInput(bitmap: Bitmap, paddedWidth: Int, buffer: FloatBuffer) {
        val width = ceil(48.0 * bitmap.width / bitmap.height).toInt().coerceIn(1, paddedWidth)
        val resized = Bitmap.createScaledBitmap(bitmap, width, 48, true)
        try {
            val pixels = IntArray(width * 48)
            resized.getPixels(pixels, 0, width, 0, 0, width, 48)
            for (channel in 0 until 3) {
                val shift = 16 - channel * 8
                for (y in 0 until 48) {
                    for (x in 0 until paddedWidth) {
                        val value = if (x < width) {
                            val pixel = ((pixels[y * width + x] shr shift) and 255) / 255f
                            (pixel - 0.5f) / 0.5f
                        } else 0f
                        buffer.put(value)
                    }
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }
}
