package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.system.Os
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Immutable image revisions are committed by an atomic manifest replacement. */
object SrStore {
    const val DIR_NAME = "sr_cache"
    const val DEFAULT_LIMIT_MB = 512
    const val UNLIMITED = -1
    const val UNKNOWN_MODEL = "-"
    val version = MutableStateFlow(0L)
    private val pages = ConcurrentHashMap<String,List<String>>()
    private val generations = ConcurrentHashMap<String,Long>()
    private val checked = ConcurrentHashMap<String,Pair<String,Boolean>>()
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    private fun book(id: Long,key: String) = "${id}_${hash(key)}"
    fun bindPages(id: Long,key: String,sourceKeys: List<String>) { pages[book(id,key)]=sourceKeys }
    private fun stem(id: Long,page: Int,key: String): String = book(id,key)+"_"+hash(pages[book(id,key)]?.getOrNull(page) ?: "page:$page")
    private fun dir(ctx: Context) = File(ctx.applicationContext.getExternalFilesDir(null),DIR_NAME).apply { mkdirs() }
    private fun marker(ctx: Context,id: Long,page: Int,key: String) = File(dir(ctx),stem(id,page,key)+".json")
    private fun read(file: File): JSONObject? = runCatching {
        file.bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
    private fun image(ctx: Context,m: JSONObject): File? {
        val name=m.optString("image")
        if(name.isEmpty() || File(name).name != name || !name.endsWith(".webp"))return null
        return File(dir(ctx),name)
    }
    private fun valid(ctx: Context,m: JSONObject,key: String): Boolean {
        if(key.isEmpty() || m.optString("key") != key)return false
        val f=image(ctx,m) ?: return false
        if(!f.isFile || f.length()==0L)return false
        val signature="${f.absolutePath}|${f.length()}|${f.lastModified()}|${m.optInt("w")}|${m.optInt("h")}"
        return checked[f.absolutePath]?.takeIf { it.first==signature }?.second ?: run {
            runCatching {
                val o=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(f.absolutePath,o)
                o.outWidth>0 && o.outWidth==m.optInt("w") && o.outHeight==m.optInt("h")
            }.getOrDefault(false).also { checked[f.absolutePath]=signature to it }
        }
    }
    fun bookHasArtifacts(ctx: Context,id: Long,key: String): Boolean =
        dir(ctx).listFiles()?.any { it.name.startsWith(book(id,key)+"_") && it.name.endsWith(".json") } == true
    fun exists(ctx: Context,id: Long,page: Int,key: String): Boolean =
        read(marker(ctx,id,page,key))?.let { valid(ctx,it,key) } ?: false
    fun modelOf(ctx: Context,id: Long,page: Int,key: String): String? = infoOf(ctx,id,page,key)?.model
    fun storedModelOrNull(ctx: Context,id: Long,page: Int,key: String): String? = modelOf(ctx,id,page,key)
    data class StoredInfo(val model: String,val width: Int,val height: Int,val scale: Int,val savedAtMs: Long,val bytes: Long,
        val fingerprint: String="",val revision: String="",val executionJson: String?=null)
    fun infoOf(ctx: Context,id: Long,page: Int,key: String): StoredInfo? {
        val m=read(marker(ctx,id,page,key)) ?: return null
        if(!valid(ctx,m,key))return null
        return StoredInfo(m.optString("model",UNKNOWN_MODEL),m.optInt("w"),m.optInt("h"),m.optInt("scale",2),
            m.optLong("ts"),image(ctx,m)!!.length(),m.optString("fp"),m.optString("revision"),m.optJSONObject("execution")?.toString())
    }
    fun generation(id: Long,page: Int,key: String): Long = generations[stem(id,page,key)] ?: 0L

    @Synchronized fun save(ctx: Context,mangaId: Long,page: Int,bitmap: Bitmap,modelName: String,mangaKey: String,
        limitMb: Int=DEFAULT_LIMIT_MB,fingerprint: String="",execution: JSONObject?=null,
        expectedGeneration: Long=generation(mangaId,page,mangaKey),canCommit: () -> Boolean={true}): Boolean {
        if(mangaKey.isBlank() || bitmap.width<=0 || bitmap.height<=0)return false
        val name=stem(mangaId,page,mangaKey)
        if(generation(mangaId,page,mangaKey)!=expectedGeneration || !canCommit())return false
        val revision=UUID.randomUUID().toString()
        val img=File(dir(ctx),"${name}_$revision.webp")
        val temp=File(dir(ctx),"${img.name}.tmp")
        val manifest=marker(ctx,mangaId,page,mangaKey)
        val old=read(manifest)
        var committed=false
        val saveBegin=System.nanoTime()
        try {
            @Suppress("DEPRECATION")
            val format=if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            FileOutputStream(temp).use { out ->
                if(!bitmap.compress(format,90,out))return false
                out.fd.sync()
            }
            if(!temp.renameTo(img))return false
            val saveElapsed=(System.nanoTime()-saveBegin)/1_000_000
            execution?.put("saveMs",saveElapsed)?.put("totalMs",execution.optLong("totalMs")+saveElapsed)
            val m=JSONObject().put("key",mangaKey).put("pageKey",pages[book(mangaId,mangaKey)]?.getOrNull(page))
                .put("model",modelName).put("fp",fingerprint).put("scale",2)
                .put("revision",revision).put("image",img.name).put("w",bitmap.width).put("h",bitmap.height)
                .put("ts",System.currentTimeMillis()).put("execution",execution)
            if(!valid(ctx,m,mangaKey) || !canCommit() || generation(mangaId,page,mangaKey)!=expectedGeneration)return false
            val pending=File(dir(ctx),"${manifest.name}.$revision.tmp")
            try {
                FileOutputStream(pending).use { out -> out.write(m.toString().toByteArray());out.fd.sync() }
                if(!canCommit() || generation(mangaId,page,mangaKey)!=expectedGeneration)return false
                Os.rename(pending.absolutePath,manifest.absolutePath)
            } finally {pending.delete()}
            committed=true
            old?.let { image(ctx,it)?.takeIf { it!=img }?.delete() }
            manageCache(ctx,limitMb,manifest.name)
            version.value+=1
            return valid(ctx,m,mangaKey)
        } catch(t: Throwable) {
            com.moe.starflow.utils.LogCollector.e("SrStore","save failed: ${t.message}",t)
            return false
        } finally {temp.delete();if(!committed)img.delete()}
    }
    fun load(ctx: Context,id: Long,page: Int,key: String): Bitmap? {
        val mf=marker(ctx,id,page,key);val m=read(mf) ?: return null
        if(!valid(ctx,m,key))return null
        val f=image(ctx,m) ?: return null
        val bmp=runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        if(bmp==null) {
            synchronized(this) {
                if(read(mf)?.optString("revision") == m.optString("revision")) {
                    generations.merge(stem(id,page,key),1L,Long::plus)
                    mf.delete();f.delete();version.value+=1
                }
            }
        } else {
            val now=System.currentTimeMillis();mf.setLastModified(now);f.setLastModified(now)
        }
        return bmp
    }
    @Synchronized fun deletePage(ctx: Context,id: Long,page: Int,key: String): Boolean {
        val name=stem(id,page,key)
        generations.merge(name,1L,Long::plus)
        val mf=marker(ctx,id,page,key);val m=read(mf)
        val f=m?.let { image(ctx,it) }
        val ok=(f==null || !f.exists() || f.delete())
        if(ok){mf.delete();version.value+=1}
        return ok && !mf.exists()
    }
    @Synchronized fun deleteManga(ctx: Context,id: Long,key: String) {
        val prefix=book(id,key)+"_"
        generations.keys.filter { it.startsWith(prefix) }.forEach { generations.merge(it,1L,Long::plus) }
        // Include in-flight pages that have not yet written a file.
        pages[book(id,key)]?.indices?.forEach { generations.merge(stem(id,it,key),1L,Long::plus) }
        dir(ctx).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        version.value+=1
    }
    @Synchronized fun clear(ctx: Context): Int {
        pages.forEach { (b, keys) -> keys.forEach { generations.merge(b+"_"+hash(it),1L,Long::plus) } }
        val n=dir(ctx).listFiles()?.count { it.delete() } ?: 0
        checked.clear();version.value+=1;return n
    }
    fun totalBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    @Synchronized fun manageCache(ctx: Context,limitMb: Int,protectedManifest: String?=null) {
        if(limitMb<0)return
        val directory=dir(ctx)
        var total=directory.listFiles()?.sumOf { it.length() } ?: 0L
        val limit=limitMb.toLong()*1024*1024
        val manifests=directory.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.lastModified() }.orEmpty()
        var changed=false
        for(mf in manifests) {
            if(total<=limit)break
            if(mf.name==protectedManifest)continue
            val m=read(mf);val img=m?.let { image(ctx,it) }
            val bytes=mf.length()+(img?.length() ?: 0L)
            if(img==null || !img.exists() || img.delete()) {mf.delete();total-=bytes;changed=true}
        }
        // Interrupted temporary writes and unreferenced immutable images have no owners.
        val owned=directory.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { read(it)?.optString("image") }?.toSet().orEmpty()
        directory.listFiles()?.filter { (it.name.endsWith(".tmp") || it.name.endsWith(".webp") && it.name !in owned) }?.forEach { it.delete() }
        checked.keys.removeAll { !File(it).isFile }
        if(changed)version.value+=1
    }
}
