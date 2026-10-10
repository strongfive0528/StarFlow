#pragma once
#include <atomic>
#include <chrono>
#include <stdexcept>
#include <thread>
#include "gpu.h"
#include "option.h"

struct SrNativeError : std::runtime_error {
    int code;
    SrNativeError(int c, const std::string& message) : std::runtime_error(message), code(c) {}
};
struct SrRunControl {
    std::atomic<bool> cancelled{false};
    std::atomic<long long> runId{0};
    int sleepMs = 0;
    int tiles = 0;
    long long throttleMs = 0;
};
inline thread_local SrRunControl* srControl = nullptr;
inline void srCheck(int rc, const char* stage) {
    if (rc) throw SrNativeError(rc, std::string(stage) + " rc=" + std::to_string(rc));
}
// ncnn sanitizes a local Option copy while loading the network. Our custom shader
// compilation happens afterward from Net::opt, so copy the same device capability
// gates there instead of requesting optional Vulkan GLSL extensions unconditionally.
inline void srSanitizeShaderOptions(ncnn::Option& opt, const ncnn::VulkanDevice* device) {
    if (!device) {
        opt.use_fp16_packed = false;
        opt.use_fp16_storage = false;
        opt.use_fp16_arithmetic = false;
        opt.use_fp16_uniform = false;
        opt.use_int8_packed = false;
        opt.use_int8_storage = false;
        opt.use_int8_arithmetic = false;
        opt.use_int8_uniform = false;
        opt.use_subgroup_ops = false;
        opt.use_cooperative_matrix = false;
        return;
    }

    const ncnn::GpuInfo& info = device->info;
    if (!info.support_fp16_packed()) opt.use_fp16_packed = false;
    if (!info.support_fp16_storage()) opt.use_fp16_storage = false;
    if (!info.support_fp16_uniform()) opt.use_fp16_uniform = false;
    if (!info.support_fp16_arithmetic()) opt.use_fp16_arithmetic = false;
    if (!info.support_int8_packed()) opt.use_int8_packed = false;
    if (!info.support_int8_storage()) opt.use_int8_storage = false;
    if (!info.support_int8_uniform()) opt.use_int8_uniform = false;
    if (!info.support_int8_arithmetic()) opt.use_int8_arithmetic = false;
    if (!info.support_cooperative_matrix()) opt.use_cooperative_matrix = false;
    if (!info.support_subgroup_ops()) opt.use_subgroup_ops = false;

    if (!opt.use_fp16_packed && !opt.use_fp16_storage) opt.use_fp16_arithmetic = false;
    if (!opt.use_int8_packed && !opt.use_int8_storage) opt.use_int8_arithmetic = false;
    if (!opt.use_fp16_arithmetic) opt.use_fp16_uniform = false;
}
// A boundary is reached only after the preceding synchronous work has finished.
inline void srTileCheckpoint() {
    if (!srControl) return;
    if (srControl->cancelled.load()) throw SrNativeError(-200, "cancelled at tile boundary");
    if (srControl->tiles > 0 && srControl->sleepMs > 0) {
        for (int i = 0; i < srControl->sleepMs; ++i) {
            if (srControl->cancelled.load()) throw SrNativeError(-200, "cancelled between tiles");
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            ++srControl->throttleMs;
        }
    }
    ++srControl->tiles;
}
struct SrAllocatorGuard {
    const ncnn::VulkanDevice* device;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
    ~SrAllocatorGuard() {
        if (device) {
            device->reclaim_blob_allocator(blob);
            device->reclaim_staging_allocator(staging);
        }
    }
};
