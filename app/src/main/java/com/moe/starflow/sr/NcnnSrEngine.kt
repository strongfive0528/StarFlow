package com.moe.starflow.sr

import android.graphics.Bitmap
import com.moe.starflow.sr.ncnn.SrNcnnNative
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** One serial ncnn engine. Buffers are reused and release cannot overtake a process. */
class NcnnSrEngine(
    private val paramFile: File,
    private val binFile: File,
    private val family: Int,
    override val scale: Int = 2,
    private val noise: Int = -1,
    private val prepadding: Int = 18,
    private val tileSize: Int = 128,
    private val fp16Arithmetic: Boolean = false,
    private val inputBlob: String = "",
    private val outputBlob: String = "",
    private val bgr: Boolean = false,
    private val precision: Int = 0,
    private val syncGap: Int = 3,
) : SuperResolutionEngine {
    @Volatile private var handle = 0L
    override val maxInputPixels: Long = Long.MAX_VALUE
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    var diagnostics: JSONObject = JSONObject(); private set
    var failureCode: Int = 0; private set
    private var failure: String? = null
    override fun lastFailDetail(): String? = failure

    @Synchronized fun initialize(): Boolean {
        if(handle != 0L) return true
        return try {
            handle = SrNcnnNative.create(family, paramFile.absolutePath, binFile.absolutePath, 0, srThreads(),
                fp16Arithmetic, inputBlob, outputBlob, bgr, precision, syncGap)
            if(handle == 0L) failure = SrNcnnNative.lastError()
            else diagnostics = JSONObject(SrNcnnNative.diagnostics(handle))
            handle != 0L
        } catch(t: Throwable) { failure = "${t.javaClass.simpleName}: ${t.message}"; false }
    }
    override fun upscale(src: Bitmap): Bitmap? = process(src, 0, SrRequest.nextRunId.incrementAndGet(), null)
    suspend fun upscaleCancellable(src: Bitmap, sleepMs: Int, runId: Long): Bitmap? =
        process(src, sleepMs, runId, currentCoroutineContext()[Job])

    private fun buffer(previous: ByteBuffer?, needed: Int): ByteBuffer {
        if(previous != null && previous.capacity() >= needed) return previous
        val rounded = ((needed.toLong()+1_048_575L)/1_048_576L*1_048_576L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ByteBuffer.allocateDirect(rounded)
    }
    @Synchronized private fun process(src: Bitmap, sleepMs: Int, runId: Long, parent: Job?): Bitmap? {
        failure=null; failureCode=0
        val h=handle
        if(h==0L) { failure="engine is not initialized"; return null }
        val watcherScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val watcher=watcherScope.launch {
            while(isActive) {
                if(parent != null && !parent.isActive) SrNcnnNative.cancel(h,runId)
                delay(20)
            }
        }
        var owned: Bitmap? = null
        var stage="RGB input/output allocation"
        try {
            parent?.ensureActive()
            val w=src.width;val height=src.height
            val ow=w.toLong()*scale;val oh=height.toLong()*scale
            if(ow<=0||oh<=0||ow>Int.MAX_VALUE||oh>Int.MAX_VALUE||ow>Int.MAX_VALUE/oh/3) {
                failure="output exceeds addressable buffer; lower input MP threshold";failureCode=-201;return null
            }
            val inBytes=(w.toLong()*height*3).toInt();val outBytes=(ow*oh*3).toInt()
            inputBuffer=buffer(inputBuffer,inBytes);outputBuffer=buffer(outputBuffer,outBytes)
            val input=inputBuffer!!;val output=outputBuffer!!
            val packBegin=System.nanoTime()
            val row=IntArray(w)
            for(y in 0 until height) {
                if(y%32==0)parent?.ensureActive()
                src.getPixels(row,0,w,0,y,w,1)
                for(x in 0 until w) {
                    val offset=(y*w+x)*3;val c=row[x]
                    input.put(offset,(c shr 16).toByte());input.put(offset+1,(c shr 8).toByte());input.put(offset+2,c.toByte())
                }
            }
            val packMs=(System.nanoTime()-packBegin)/1_000_000
            val inferBegin=System.nanoTime()
            stage="model inference"
            val ok=SrNcnnNative.process(h,input,output,w,height,scale,noise,prepadding,tileSize,sleepMs,runId)
            val inferMs=(System.nanoTime()-inferBegin)/1_000_000
            diagnostics=JSONObject(SrNcnnNative.diagnostics(h)).put("rgbPackMs",packMs).put("inferenceMs",inferMs)
            failureCode=diagnostics.optInt("code")
            if(failureCode == -200) throw CancellationException("native SR cancelled")
            parent?.ensureActive()
            if(!ok) {
                failure=diagnostics.optString("detail",SrNcnnNative.lastError())
                return null
            }
            val unpackBegin=System.nanoTime()
            stage="output bitmap allocation"
            val bitmap=Bitmap.createBitmap(ow.toInt(),oh.toInt(),Bitmap.Config.ARGB_8888);owned=bitmap
            val outRow=IntArray(ow.toInt())
            for(y in 0 until oh.toInt()) {
                if(y%32==0)parent?.ensureActive()
                for(x in outRow.indices) {
                    val offset=(y*ow.toInt()+x)*3
                    outRow[x]=(0xff shl 24) or ((output.get(offset).toInt() and 255) shl 16) or
                        ((output.get(offset+1).toInt() and 255) shl 8) or (output.get(offset+2).toInt() and 255)
                }
                bitmap.setPixels(outRow,0,outRow.size,0,y,outRow.size,1)
            }
            diagnostics.put("rgbUnpackMs",(System.nanoTime()-unpackBegin)/1_000_000)
            owned=null;return bitmap
        } catch(e: CancellationException) { throw e }
          catch(e: OutOfMemoryError) {inputBuffer=null;outputBuffer=null;failureCode=-100;failure="$stage: memory allocation failed; lower input MP threshold";return null}
          catch(t: Throwable) {failure="$stage: ${t.javaClass.simpleName}: ${t.message}";return null}
        finally {owned?.recycle();watcher.cancel();watcherScope.cancel()}
    }
    private var leases=0
    private var closing=false
    @Synchronized fun retain(): Boolean { if(closing)return false;leases++;return true }
    @Synchronized fun releaseLease() { leases--;if(leases==0 && closing)releaseNow() }
    @Synchronized override fun release() { closing=true;if(leases==0)releaseNow() }
    private fun releaseNow() {
        val h=handle;handle=0L
        if(h!=0L)SrNcnnNative.release(h)
        inputBuffer=null;outputBuffer=null
    }
}
