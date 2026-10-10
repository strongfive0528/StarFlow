package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.moe.starflow.sr.ncnn.SrNcnnNative
import com.moe.starflow.sr.anime4k.Anime4kEngine
import com.moe.starflow.sr.anime4k.Anime4kMode
import java.util.concurrent.Executors

interface SuperResolutionEngine {
    val scale: Int
    val maxInputPixels: Long
    fun upscale(src: Bitmap): Bitmap?
    fun lastFailDetail(): String?
    fun release()
}
enum class SrStep { SR_MODEL, ANIME4K }

/** Explicit SR requests run ncnn. Anime4K is a separate reader display operation. */
object SuperResolutionEngines {
    private var cachedKey: String? = null
    private var cachedEngine: NcnnSrEngine? = null
    private var cachedAnimeMode: Anime4kMode? = null
    private var cachedAnime: Anime4kEngine? = null
    private val releases = Executors.newSingleThreadExecutor { r -> Thread(r,"sr-release").apply { isDaemon=true } }
    class EnginePick(val engine: SuperResolutionEngine?, val reason: SrFailReason? = null, val detail: String? = null)
    fun resolveSteps(srEnabled: Boolean, srModelUsable: Boolean, anime4kEnabled: Boolean): List<SrStep> =
        if(srEnabled && srModelUsable) listOf(SrStep.SR_MODEL)
        else if(!srEnabled && anime4kEnabled) listOf(SrStep.ANIME4K) else emptyList()

    fun obtain(context: Context, prefs: SharedPreferences, request: SrRequest = SrRequest.capture(context,prefs), retain: Boolean = false): EnginePick {
        val key=request.model ?: return EnginePick(null,SrFailReason.NO_MODEL_SELECTED)
        val info=request.info ?: return EnginePick(null,SrFailReason.ENGINE_INIT_FAILED,"model metadata is missing")
        val error=SrModelManager.validate(context,key)
        if(error != null) return EnginePick(null, if(error.contains("missing")) SrFailReason.MODEL_FILE_MISSING else SrFailReason.MODEL_CORRUPT,error)
        val cacheKey=key.name+"#"+request.weightRevision+"#"+request.options.engineKey()
        synchronized(this) {
            cachedEngine?.let { if(cachedKey==cacheKey && (!retain || it.retain()))return EnginePick(it) }
        }
        val family=when(info.srFamily) {
            "waifu2x" -> SrNcnnNative.FAMILY_WAIFU2X
            "realcugan" -> SrNcnnNative.FAMILY_REALCUGAN
            "realesrgan" -> SrNcnnNative.FAMILY_REALESRGAN
            "generic" -> SrNcnnNative.FAMILY_GENERIC
            else -> return EnginePick(null,SrFailReason.ENGINE_INIT_FAILED,"unsupported model family: ${info.srFamily}")
        }
        val pair=SrModelManager.ncnnPair(context,key) ?: return EnginePick(null,SrFailReason.MODEL_FILE_MISSING)
        val o=request.options
        val engine=NcnnSrEngine(pair.first,pair.second,family,info.srScale,info.srNoise,info.srPrepad,o.effectiveTile,
            info.srFp16Arithmetic && o.fp16Arithmetic,info.srBlobIn,info.srBlobOut,info.srBgr,o.precision,info.srSyncGap)
        if(!engine.initialize()) return EnginePick(null,SrFailReason.ENGINE_INIT_FAILED,engine.lastFailDetail())
        synchronized(this) {
            val old=cachedEngine
            cachedKey=cacheKey;cachedEngine=engine
            if(retain)engine.retain()
            if(old!=null)releases.execute { old.release() }
        }
        return EnginePick(engine)
    }
    fun upscaleForReader(context: Context,prefs: SharedPreferences,src: Bitmap): SrOutcome {
        if(!SrSettings.isEnabledForReader(prefs)) return SrOutcome.fail(SrFailReason.DISABLED)
        val pick=obtain(context,prefs)
        val engine=pick.engine ?: return SrOutcome.fail(pick.reason ?: SrFailReason.ENGINE_INIT_FAILED,pick.detail)
        return engine.upscale(src)?.let { SrOutcome.ok(it) } ?: SrOutcome.fail(SrFailReason.INFERENCE_FAILED,engine.lastFailDetail())
    }
    fun isSrModelUsable(context: Context,prefs: SharedPreferences): Boolean =
        SrModelManager.getActiveKey(prefs)?.let { SrModelManager.isDownloaded(context,it) } ?: false

    @Synchronized fun obtainAnime4k(context: Context,prefs: SharedPreferences): EnginePick {
        val mode=Anime4kMode.fromPrefs(prefs)
        cachedAnime?.let { if(cachedAnimeMode==mode)return EnginePick(it) }
        releaseAnime4k()
        val engine=Anime4kEngine(context.applicationContext,mode)
        if(!engine.initialize())return EnginePick(null,SrFailReason.ANIME4K_INIT_FAILED,mode.id)
        cachedAnime=engine;cachedAnimeMode=mode
        return EnginePick(engine)
    }
    fun enhanceWithAnime4k(context: Context,prefs: SharedPreferences,src: Bitmap): SrOutcome {
        val pick=obtainAnime4k(context,prefs)
        val engine=pick.engine ?: return SrOutcome.fail(pick.reason ?: SrFailReason.ANIME4K_INIT_FAILED,pick.detail)
        if(src.width.toLong()*src.height>engine.maxInputPixels)return SrOutcome.fail(SrFailReason.ANIME4K_SOURCE_TOO_LARGE)
        return engine.upscale(src)?.let { SrOutcome.ok(it) } ?: SrOutcome.fail(SrFailReason.INFERENCE_FAILED,engine.lastFailDetail())
    }
    @Synchronized fun releaseSrModel() { val old=cachedEngine;cachedEngine=null;cachedKey=null;if(old!=null)releases.execute { old.release() } }
    @Synchronized fun releaseAnime4k() { val old=cachedAnime;cachedAnime=null;cachedAnimeMode=null;if(old!=null)releases.execute { old.release() } }
    @Synchronized fun release() { releaseSrModel();releaseAnime4k() }
}
