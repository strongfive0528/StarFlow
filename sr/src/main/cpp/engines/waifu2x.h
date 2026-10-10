// waifu2x implemented with ncnn library

#ifndef WAIFU2X_H
#define WAIFU2X_H

#include <string>

// ncnn
#include "net.h"
#include "gpu.h"
#include "layer.h"

class Waifu2x
{
public:
    Waifu2x(int gpuid, bool tta_mode = false, int num_threads = 1);
    ~Waifu2x();

#if _WIN32
    int load(const std::wstring& parampath, const std::wstring& modelpath);
#else
    int load(const std::string& parampath, const std::string& modelpath);
#endif

    int process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;

    int process_same(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;
    bool same_padding = false;

    int process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;

public:
    // waifu2x parameters
    int noise;
    int scale;
    int tilesize;
    int prepadding;

    /**
     * 算力精度开关（逐模型来自 `downloadinfo.json` 的 `fp16Arithmetic`，默认关 = 保持旧行为）。
     * ⚠️ 必须在 `load()` **之前**设置：ncnn 的 `opt` 是 net 级、load 时就固化进管线。
     * ⚠️ 只开 `use_fp16_storage` 只省显存；**开了本项才是真的用 FP16 算**（移动 GPU 上明显更快）。
     */
    bool fp16_arithmetic = false;
    bool storage_fp16 = true;
    bool effective_fp16 = false;
    bool effective_arithmetic = false;

    /**
     * 通用 ncnn 模型的**输入/输出 blob 名**（默认 = nihui waifu2x 那套）。
     * SPAN NomosUni / sudo UltraCompact / W2xEX / AnimeJaNai 这些模型的 blob 名不是
     * Input1/Eltwise4，逐模型由 `downloadinfo.json` 给出（对齐参照项目的「一个通用 ncnn 入口」）。
     */
    std::string input_blob = "Input1";
    std::string output_blob = "Eltwise4";

    /**
     * 输入是否 BGR 排布。⚠️ 默认**保持原行为**：上游按平台切换（Windows 用 BGR、其余用 RGB），
     * 这里把它从编译期改成运行期，只有清单里标了 `bgr: true` 的模型才走 BGR 分支。
     */
#if _WIN32
    bool bgr = true;
#else
    bool bgr = false;
#endif

private:
    ncnn::VulkanDevice* vkdev;
    ncnn::Net net;
    ncnn::Pipeline* waifu2x_preproc;
    ncnn::Pipeline* waifu2x_postproc;
    ncnn::Layer* bicubic_2x;
    bool tta_mode;
};

#endif // WAIFU2X_H
