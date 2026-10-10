package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

/**
 * 所有可下载模型的统一标识。
 *
 * 命名规则：
 * - 单文件模型：单一枚举值（RT_DETR_V2、PP_OCR_V5_DET 等）
 * - 多文件模型：`*_GROUP` 后缀（MANGA_OCR_GROUP、NLLB_GROUP），由 Repository 展开成多个 FileInfo
 *
 * 用作 JSON 配置 `model_key` 字段值（`ModelKey.valueOf(keyStr)`），错误键名会被跳过。
 */
enum class ModelKey(val stableId: Int) {
    /** RT-DETR-V2 漫画气泡/文字检测模型（单文件 ~11MB） */
    RT_DETR_V2(1001),

    /** manga-ocr 识别组（encoder.onnx + decoder.onnx + vocab.txt） */
    MANGA_OCR_GROUP(1009),

    /** PP-OCRv5 文字检测模型（单文件 ~4.6MB） */
    PP_OCR_V5_DET(1002),

    /** PP-OCRv5 中文识别模型（rec_zh.onnx + rec_zh_dict.txt） */
    PP_OCR_V5_REC_ZH(1003),

    /** PP-OCRv5 英文识别模型（rec_en.onnx + rec_en_dict.txt） */
    PP_OCR_V5_REC_EN(1004),

    /** PP-OCRv5 韩文识别模型（rec_ko.onnx + rec_ko_dict.txt） */
    PP_OCR_V5_REC_KO(1005),

    /** PP-OCRv5 俄文识别模型（rec_ru.onnx + rec_ru_dict.txt） */
    PP_OCR_V5_REC_RU(1006),

    /** PP-OCRv6 medium 检测模型（单文件 ~60MB） */
    PP_OCR_V6_MEDIUM_DET(1007),

    /** PP-OCRv6 medium 识别模型（单文件 ~74MB） */
    PP_OCR_V6_MEDIUM_REC(1008),

    /** NLLB 翻译模型组（NLLB_encoder.onnx + NLLB_decoder.onnx + sentencepiece_bpe.model） */
    NLLB_GROUP(2010),

    /** Hy-MT2 本地翻译模型（Hy-MT2-1.8B-1.25Bit.gguf 单文件 ~440MB） */
    HY_MT2_GROUP(2011),

    /**
     * Hy-MT2 1.8B **Q4_K_M**（标准量化，单文件 ~1.08GB）—— 官方 HuggingFace 仓库直接下载。
     * 与 1.25-bit 一样是「可下载的内置模型」，不打进 APK；标准量化无需重打标。
     */
    HY_MT2_Q4_KM(2012),

    // ══════════════════════════════════════════════════════════════════
    // 超分（SR）模型组 —— 单文件 ONNX，全部走下载（不打进 APK）
    //
    // 一族一个「等级」条目：AnimeJaNai HD V3.1 有 4 个等级
    // （均衡/性能 × 标准/锐化），另加 SD 紧凑档。
    // 运行时由 `SuperResolutionEngine` 按 [stableId] 反查；模型管理页「超分」Tab
    // 与 OCR 用同一套下载/状态/浏览器机制（见 ModelManagementFragment）。
    // ══════════════════════════════════════════════════════════════════

    /** Real-CUGAN SE（models-se/up2x-no-denoise；参照项目档位 0 · No Denoise） */
    SR_REALCUGAN_SE(3040),
    /** Real-CUGAN Pro（models-pro/up2x-no-denoise；参照项目档位 1 · No Denoise） */
    SR_REALCUGAN_PRO(3041),
    /** Real-CUGAN Nose（models-nose/up2x-no-denoise；单段网络 in0→out0，走通用入口） */
    SR_REALCUGAN_NOSE(3042),
    /** Waifu2x cunet（noise2_scale2.0x；参照项目档位 4 · Waifu2x，默认 noise=2） */
    SR_WAIFU2X_CUNET(3043),
    /** Waifu2x Fast / upconv_7（noise2_scale2.0x；参照项目档位 5） */
    SR_WAIFU2X_FAST(3044),
    /** Real-ESRGAN animevideov3 x2（参照项目档位 2 · 倍率 2） */
    SR_REALESRGAN(3045),
    /** W2xEX Universal Fast（data→output，BGR，padding 10；参照项目档位 6） */
    SR_W2XEX_UNIVERSAL_FAST(3046),
    /** W2xEX Omni Mini V2（data→output，BGR，padding 10；参照项目档位 8） */
    SR_W2XEX_OMNI_MINI_V2(3047),
    /** W2xEX Photo Small（data→output，BGR，padding 10；参照项目档位 9） */
    SR_W2XEX_PHOTO_SMALL(3048),
    /** sudo UltraCompact（input→output，RGB，padding 10；参照项目档位 18） */
    SR_SUDO_ULTRA_COMPACT(3049),
    /** SPAN NomosUni Photo（in0→out0，BGR，padding 24；参照项目档位 19） */
    SR_SPAN_NOMOSUNI(3050);
}