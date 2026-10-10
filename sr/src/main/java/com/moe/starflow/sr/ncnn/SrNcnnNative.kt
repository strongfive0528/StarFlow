package com.moe.starflow.sr.ncnn

import java.nio.ByteBuffer

/** Compact RGB8 direct buffers. Creation freezes precision and model family; processing sets tile,
 * throttling and run ID. diagnostics reports effective backend/options/errors. Release is idempotent
 * and in-flight native calls retain their engine. CPU supports W2X/CUGAN/generic; ESRGAN needs Vulkan.
 */
object SrNcnnNative {

    const val FAMILY_WAIFU2X = 0

    const val FAMILY_REALCUGAN = 2

    const val FAMILY_REALESRGAN = 3

    const val FAMILY_GENERIC = 4

    init {
        System.loadLibrary("starflow_sr")
    }

    external fun create(
        family: Int,
        paramPath: String,
        binPath: String,
        gpuId: Int,
        numThreads: Int,
        fp16Arithmetic: Boolean,

        inputBlob: String = "",

        outputBlob: String = "",

        bgr: Boolean = false,
        precision: Int = 0,
        syncGap: Int = 3,
    ): Long

    external fun process(
        handle: Long,
        inBuf: ByteBuffer,
        outBuf: ByteBuffer,
        w: Int,
        h: Int,
        scale: Int,
        noise: Int,
        prepadding: Int,
        tileSize: Int,
        tileSleepMs: Int = 0,
        runId: Long = 0,
    ): Boolean

    external fun cancel(handle: Long, runId: Long)

    external fun diagnostics(handle: Long): String
    external fun lastError(): String

    external fun release(handle: Long)

    external fun gpuName(): String

    external fun gpuCount(): Int

    external fun heapBudgetMb(): Int

    external fun autoTileSize(): Int
}
