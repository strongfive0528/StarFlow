// realesrgan implemented with ncnn library

#ifndef REALESRGAN_H
#define REALESRGAN_H

#include <string>

// ncnn
#include "net.h"
#include "gpu.h"
#include "layer.h"

class RealESRGAN
{
public:
    RealESRGAN(int gpuid, bool tta_mode = false, int num_threads = 1);
    ~RealESRGAN();

#if _WIN32
    int load(const std::wstring& parampath, const std::wstring& modelpath);
#else
    int load(const std::string& parampath, const std::string& modelpath);
#endif

    int process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const;

public:
    // realesrgan parameters
    int scale;
    int tilesize;
    int prepadding;

    /**
     * 算力精度开关（逐模型来自 `downloadinfo.json` 的 `fp16Arithmetic`，默认关 = 保持旧行为）。
     * ⚠️ 必须在 `load()` **之前**设置：ncnn 的 `opt` 是 net 级、load 时就固化进管线。
     * ⚠️ 只开 `use_fp16_storage` 只省显存；**开了本项才是真的用 FP16 算**（移动 GPU 上明显更快）。
     */
    bool fp16_arithmetic = false;

private:
    ncnn::Net net;
    ncnn::Pipeline* realesrgan_preproc;
    ncnn::Pipeline* realesrgan_postproc;
    ncnn::Layer* bicubic_2x;
    ncnn::Layer* bicubic_3x;
    ncnn::Layer* bicubic_4x;
    bool tta_mode;
};

#endif // REALESRGAN_H
