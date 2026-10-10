// ncnn bridge. Handles are monotonic IDs; in-flight calls retain the engine.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <climits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include "gpu.h"
#include "net.h"
#include "engines/sr_control.h"
#include "waifu2x.h"
#include "realcugan.h"
#include "realesrgan.h"

namespace {
constexpr int W2X=0, CUGAN=2, ESRGAN=3, GENERIC=4;
struct SrHandle {
    int family=-1, gpu=-1, threads=1, tile=0, syncgap=3, effectiveSyncgap=3;
    bool fp16=false, arithmetic=false;
    std::unique_ptr<Waifu2x> w2x;
    std::unique_ptr<RealCUGAN> cugan;
    std::unique_ptr<RealESRGAN> esrgan;
    std::mutex mutex;
    SrRunControl control;
    int code=0;
    std::string detail;
};
std::mutex registryLock;
std::map<jlong,std::shared_ptr<SrHandle>> registry;
jlong nextId=0;
std::once_flag gpuOnce;
int gpuCount=0, heapMb=0;
thread_local std::string lastError;
void ensureGpu() {
    std::call_once(gpuOnce, [] {
        ncnn::create_gpu_instance();
        gpuCount=ncnn::get_gpu_count();
        if(gpuCount>0) heapMb=(int)ncnn::get_gpu_device(0)->get_heap_budget();
    });
}
int autoTile() { return gpuCount==0?128:heapMb>=1900?400:heapMb>=550?300:heapMb>=190?200:100; }
std::shared_ptr<SrHandle> acquire(jlong id) {
    std::lock_guard<std::mutex> guard(registryLock);
    auto it=registry.find(id);
    return it==registry.end()?nullptr:it->second;
}
std::string str(JNIEnv* e,jstring s) {
    if(!s) return {};
    const char* v=e->GetStringUTFChars(s,nullptr);
    std::string out=v?v:"";
    if(v)e->ReleaseStringUTFChars(s,v);
    return out;
}
std::string quote(const std::string& s) {
    std::string out="\"";
    for(unsigned char c:s) {
        if(c=='"'||c=='\\') { out+='\\';out+=c; }
        else if(c<32) out+=' ';
        else out+=c;
    }
    return out+'"';
}
std::string diagnostic(const SrHandle& h) {
    std::ostringstream s;
    s<<"{\"backend\":"<<quote(h.gpu<0?"CPU":"Vulkan")
     <<",\"deviceName\":"<<quote(h.gpu<0?"CPU":ncnn::get_gpu_info(h.gpu).device_name())
     <<",\"precision\":"<<quote(h.fp16?"FP16":"FP32")
     <<",\"fp16Arithmetic\":"<<(h.arithmetic?"true":"false")
     <<",\"syncGap\":"<<h.effectiveSyncgap
     <<",\"threads\":"<<h.threads<<",\"tile\":"<<h.tile
     <<",\"blocks\":"<<h.control.tiles<<",\"throttleMs\":"<<h.control.throttleMs
     <<",\"code\":"<<h.code<<",\"detail\":"<<quote(h.detail)<<"}";
    return s.str();
}
}
extern "C" {
JNIEXPORT jlong JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_create(
 JNIEnv* env,jclass,jint family,jstring paramPath,jstring binPath,jint gpuId,jint threads,
 jboolean arithmetic,jstring inputBlob,jstring outputBlob,jboolean bgr,jint precision,jint syncgap) {
    lastError.clear();
    try {
        ensureGpu();
        int gpu=gpuId;
        if(gpu>=0 && gpuCount==0) gpu=-1;
        if(gpu>=gpuCount && gpu>=0) throw SrNativeError(-301,"Vulkan device is unavailable");
        if(gpu<0 && family==ESRGAN) throw SrNativeError(-301,"Real-ESRGAN requires Vulkan; this model has no CPU backend");
        if(family!=W2X && family!=CUGAN && family!=ESRGAN && family!=GENERIC)
            throw SrNativeError(-201,"unsupported model family");
        auto h=std::make_shared<SrHandle>();
        h->family=family;h->gpu=gpu;h->threads=std::max(1,(int)threads);h->syncgap=syncgap;
        const auto param=str(env,paramPath),bin=str(env,binPath);
        if(family==W2X||family==GENERIC) {
            h->w2x=std::make_unique<Waifu2x>(gpu,false,h->threads);
            auto& e=*h->w2x;
            e.storage_fp16=precision==0;e.fp16_arithmetic=arithmetic;
            e.same_padding=family==GENERIC;
            const auto ib=str(env,inputBlob),ob=str(env,outputBlob);
            if(!ib.empty())e.input_blob=ib;
            if(!ob.empty())e.output_blob=ob;
            e.bgr=bgr;
            srCheck(e.load(param,bin),"model load");
            h->fp16=e.effective_fp16;h->arithmetic=e.effective_arithmetic;
        } else if(family==CUGAN) {
            h->cugan=std::make_unique<RealCUGAN>(gpu,false,h->threads);
            auto& e=*h->cugan;
            e.storage_fp16=precision==0;e.fp16_arithmetic=arithmetic;
            srCheck(e.load(param,bin),"model load");
            h->fp16=e.effective_fp16;h->arithmetic=e.effective_arithmetic;
        } else {
            h->esrgan=std::make_unique<RealESRGAN>(gpu,false,h->threads);
            auto& e=*h->esrgan;
            e.storage_fp16=precision==0;e.fp16_arithmetic=arithmetic;
            srCheck(e.load(param,bin),"model load");
            h->fp16=e.effective_fp16;h->arithmetic=e.effective_arithmetic;
        }
        std::lock_guard<std::mutex> guard(registryLock);
        const auto id=++nextId;registry[id]=h;return id;
    } catch(const std::exception& e) {lastError=e.what();}
      catch(...) {lastError="native initialization failed";}
    return 0;
}
JNIEXPORT jboolean JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_process(
 JNIEnv* env,jclass,jlong handle,jobject inBuf,jobject outBuf,jint w,jint ht,jint scale,
 jint noise,jint padding,jint tile,jint sleepMs,jlong runId) {
    auto h=acquire(handle);
    if(!h){lastError="engine handle was released";return JNI_FALSE;}
    std::lock_guard<std::mutex> guard(h->mutex);
    h->code=0;h->detail.clear();h->control.tiles=0;h->control.throttleMs=0;
    h->control.cancelled=false;h->control.runId=runId;h->control.sleepMs=std::max(0,(int)sleepMs);
    srControl=&h->control;
    struct Scope { ~Scope(){srControl=nullptr;} } scope;
    try {
        if(w<=0||ht<=0||scale<=0||scale>4) throw SrNativeError(-201,"invalid input dimensions/scale");
        const jlong ow=(jlong)w*scale,oh=(jlong)ht*scale;
        if(ow>INT_MAX||oh>INT_MAX||ow>INT_MAX/oh/3)
            throw SrNativeError(-201,"output exceeds addressable RGB buffer; lower input MP threshold");
        const jlong inputBytes=(jlong)w*ht*3,outputBytes=ow*oh*3;
        if(env->GetDirectBufferCapacity(inBuf)<inputBytes||env->GetDirectBufferCapacity(outBuf)<outputBytes)
            throw SrNativeError(-201,"RGB buffer capacity does not match dimensions");
        auto* in=(unsigned char*)env->GetDirectBufferAddress(inBuf);
        auto* out=(unsigned char*)env->GetDirectBufferAddress(outBuf);
        if(!in||!out)throw SrNativeError(-201,"RGB buffers must be direct");
        h->tile=tile<=0?autoTile():std::max(32,(int)tile);
        ncnn::Mat input(w,ht,in,(size_t)3u,3),output((int)ow,(int)oh,out,(size_t)3u,3);
        int rc=-1;
        if(h->w2x) {
            auto& e=*h->w2x;e.noise=noise;e.scale=scale;e.tilesize=h->tile;e.prepadding=padding;
            rc=e.process(input,output);
        } else if(h->cugan) {
            auto& e=*h->cugan;e.noise=noise;e.scale=scale;e.tilesize=h->tile;e.prepadding=padding;
            h->effectiveSyncgap=h->syncgap==3 && (w<=64 || ht<=64)?1:h->syncgap;
            e.syncgap=h->effectiveSyncgap;
            rc=e.process(input,output);
        } else if(h->esrgan) {
            auto& e=*h->esrgan;e.scale=scale;e.tilesize=h->tile;e.prepadding=padding;
            rc=e.process(input,output);
        }
        srCheck(rc,"model process");
        if(h->control.cancelled.load())throw SrNativeError(-200,"cancelled before output commit");
        if(!output.data||output.w!=ow||output.h!=oh||output.elemsize!=3||output.elempack!=3||output.elembits()!=8)
            throw SrNativeError(-201,"output layout/shape does not match RGB contract");
        if(output.data!=out)memcpy(out,output.data,(size_t)outputBytes);
        return JNI_TRUE;
    } catch(const SrNativeError& e){h->code=e.code;h->detail=e.what();}
      catch(const std::bad_alloc&){h->code=-100;h->detail="native memory allocation failed; reduce tile size";}
      catch(const std::exception& e){h->code=-202;h->detail=e.what();}
      catch(...){h->code=-202;h->detail="unclassified native inference failure";}
    lastError=h->detail;
    return JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_cancel(JNIEnv*,jclass,jlong id,jlong runId){
    auto h=acquire(id);if(h&&h->control.runId.load()==runId)h->control.cancelled=true;
}
JNIEXPORT jstring JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_diagnostics(JNIEnv* env,jclass,jlong id){
    auto h=acquire(id);if(!h)return env->NewStringUTF("{}");
    std::lock_guard<std::mutex> guard(h->mutex);
    return env->NewStringUTF(diagnostic(*h).c_str());
}
JNIEXPORT jstring JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_lastError(JNIEnv* env,jclass){return env->NewStringUTF(lastError.c_str());}
JNIEXPORT void JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_release(JNIEnv*,jclass,jlong id){
    std::shared_ptr<SrHandle> old;
    {std::lock_guard<std::mutex> guard(registryLock);auto it=registry.find(id);if(it==registry.end())return;old=it->second;registry.erase(it);}
    // Destruction waits for the last in-flight strong reference, without blocking the UI.
}
JNIEXPORT jstring JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_gpuName(JNIEnv* env,jclass){ensureGpu();return env->NewStringUTF(gpuCount>0?ncnn::get_gpu_info(0).device_name():"CPU");}
JNIEXPORT jint JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_gpuCount(JNIEnv*,jclass){ensureGpu();return gpuCount;}
JNIEXPORT jint JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_heapBudgetMb(JNIEnv*,jclass){ensureGpu();return heapMb;}
JNIEXPORT jint JNICALL Java_com_moe_starflow_sr_ncnn_SrNcnnNative_autoTileSize(JNIEnv*,jclass){ensureGpu();return autoTile();}
}
