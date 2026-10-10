// waifu2x implemented with ncnn library

#include "waifu2x.h"
#include "sr_control.h"
#include <cmath>

// ⚠️ 通道序由实例字段 `bgr` 决定（原来是 `#if _WIN32` 编译期切换）

#include <algorithm>
#include <vector>

#include "waifu2x_preproc.comp.hex.h"
#include "waifu2x_postproc.comp.hex.h"
#include "waifu2x_preproc_tta.comp.hex.h"
#include "waifu2x_postproc_tta.comp.hex.h"

Waifu2x::Waifu2x(int gpuid, bool _tta_mode, int num_threads)
{
    vkdev = gpuid == -1 ? 0 : ncnn::get_gpu_device(gpuid);

    net.opt.num_threads = num_threads;

    waifu2x_preproc = 0;
    waifu2x_postproc = 0;
    bicubic_2x = 0;
    tta_mode = _tta_mode;
}

Waifu2x::~Waifu2x()
{
    // cleanup preprocess and postprocess pipeline
    {
        delete waifu2x_preproc;
        delete waifu2x_postproc;
    }

    if (bicubic_2x) bicubic_2x->destroy_pipeline(net.opt);
    delete bicubic_2x;
}

#if _WIN32
int Waifu2x::load(const std::wstring& parampath, const std::wstring& modelpath)
#else
int Waifu2x::load(const std::string& parampath, const std::string& modelpath)
#endif
{
    net.opt.use_vulkan_compute = vkdev != nullptr;
    if (vkdev) net.set_vulkan_device(vkdev);
    effective_fp16 = storage_fp16 && vkdev && vkdev->info.support_fp16_storage();
    effective_arithmetic = effective_fp16 && fp16_arithmetic && vkdev->info.support_fp16_arithmetic();
    net.opt.use_fp16_packed = effective_fp16;
    net.opt.use_fp16_storage = effective_fp16;
    net.opt.use_fp16_arithmetic = effective_arithmetic;
    net.opt.use_int8_storage = true;
    net.opt.use_int8_arithmetic = false;
    srSanitizeShaderOptions(net.opt, vkdev);

#if _WIN32
    {
        FILE* fp = _wfopen(parampath.c_str(), L"rb");
        if (!fp)
        {
            fwprintf(stderr, L"_wfopen %ls failed\n", parampath.c_str());
        }

        srCheck(net.load_param(fp), "model load");

        fclose(fp);
    }
    {
        FILE* fp = _wfopen(modelpath.c_str(), L"rb");
        if (!fp)
        {
            fwprintf(stderr, L"_wfopen %ls failed\n", modelpath.c_str());
        }

        srCheck(net.load_model(fp), "model load");

        fclose(fp);
    }
#else
    srCheck(net.load_param(parampath.c_str()), "model load");
    srCheck(net.load_model(modelpath.c_str()), "model load");
#endif

    // The generic same-padding path feeds tiles directly to the network and never uses
    // Waifu2x's custom preprocess/postprocess shaders or bicubic alpha pipeline.
    if (vkdev && !same_padding)
    {
        std::vector<ncnn::vk_specialization_type> specializations(1);
        specializations[0].i = bgr ? 1 : 0;

        {
            std::vector<uint32_t> spirv;
            static ncnn::Mutex lock;
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty())
                {
                    if (tta_mode)
                        srCheck(compile_spirv_module(waifu2x_preproc_tta_comp_data, sizeof(waifu2x_preproc_tta_comp_data), net.opt, spirv), "shader compilation");
                    else
                        srCheck(compile_spirv_module(waifu2x_preproc_comp_data, sizeof(waifu2x_preproc_comp_data), net.opt, spirv), "shader compilation");
                }
            }

            waifu2x_preproc = new ncnn::Pipeline(vkdev);
            waifu2x_preproc->set_optimal_local_size_xyz(8, 8, 3);
            srCheck(waifu2x_preproc->create(spirv.data(), spirv.size() * 4, specializations), "shader pipeline");
        }

        {
            std::vector<uint32_t> spirv;
            static ncnn::Mutex lock;
            {
                ncnn::MutexLockGuard guard(lock);
                if (spirv.empty())
                {
                    if (tta_mode)
                        srCheck(compile_spirv_module(waifu2x_postproc_tta_comp_data, sizeof(waifu2x_postproc_tta_comp_data), net.opt, spirv), "shader compilation");
                    else
                        srCheck(compile_spirv_module(waifu2x_postproc_comp_data, sizeof(waifu2x_postproc_comp_data), net.opt, spirv), "shader compilation");
                }
            }

            waifu2x_postproc = new ncnn::Pipeline(vkdev);
            waifu2x_postproc->set_optimal_local_size_xyz(8, 8, 3);
            srCheck(waifu2x_postproc->create(spirv.data(), spirv.size() * 4, specializations), "shader pipeline");
        }
    }

    // bicubic 2x for alpha channel (unused by same-padding generic models)
    if (!same_padding)
    {
        bicubic_2x = ncnn::create_layer("Interp");
        bicubic_2x->vkdev = vkdev;

        ncnn::ParamDict pd;
        pd.set(0, 3);// bicubic
        pd.set(1, 2.f);
        pd.set(2, 2.f);
        srCheck(bicubic_2x->load_param(pd), "bicubic pipeline");

        srCheck(bicubic_2x->create_pipeline(net.opt), "bicubic pipeline");
    }

    return 0;
}

int Waifu2x::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    if (same_padding) return process_same(inimage, outimage);
    if (!vkdev)
    {
        // cpu only
        return process_cpu(inimage, outimage);
    }

    if (noise == -1 && scale == 1)
    {
        outimage = inimage;
        return 0;
    }

    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();
    SrAllocatorGuard allocator_guard{vkdev, blob_vkallocator, staging_vkallocator};

    ncnn::Option opt = net.opt;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    // each tile 400x400
    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

    const size_t in_out_tile_elemsize = (opt.use_fp16_storage || opt.use_fp16_packed) ? 2u : 4u;

    //#pragma omp parallel for num_threads(2)
    for (int yi = 0; yi < ytiles; yi++)
    {
        const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;

        int prepadding_bottom = prepadding;
        if (scale == 1)
        {
            prepadding_bottom += (tile_h_nopad + 3) / 4 * 4 - tile_h_nopad;
        }
        if (scale == 2)
        {
            prepadding_bottom += (tile_h_nopad + 1) / 2 * 2 - tile_h_nopad;
        }

        int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding_bottom, h);

        ncnn::Mat in;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
        {
            in = ncnn::Mat(w, (in_tile_y1 - in_tile_y0), (unsigned char*)pixeldata + (size_t)in_tile_y0 * w * channels, (size_t)channels, 1);
        }
        else
        {
            if (channels == 3)
            {
                in = ncnn::Mat::from_pixels(pixeldata + (size_t)in_tile_y0 * w * channels, (bgr ? ncnn::Mat::PIXEL_BGR2RGB : ncnn::Mat::PIXEL_RGB), w, (in_tile_y1 - in_tile_y0));
                if(in.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
            }
            if (channels == 4)
            {
                in = ncnn::Mat::from_pixels(pixeldata + (size_t)in_tile_y0 * w * channels, (bgr ? ncnn::Mat::PIXEL_BGRA2RGBA : ncnn::Mat::PIXEL_RGBA), w, (in_tile_y1 - in_tile_y0));
                if(in.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
            }
        }

        ncnn::VkCompute cmd(vkdev);

        // upload
        ncnn::VkMat in_gpu;
        {
            cmd.record_clone(in, in_gpu, opt);

            if (xtiles > 1)
            {
                srCheck(cmd.submit_and_wait(), "Vulkan submit");
                cmd.reset();
            }
        }

        int out_tile_y0 = std::max(yi * TILE_SIZE_Y, 0);
        int out_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h);

        ncnn::VkMat out_gpu;
        if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
        {
            { out_gpu.create(w * scale, (out_tile_y1 - out_tile_y0) * scale, (size_t)channels, 1, blob_vkallocator); if(out_gpu.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
        }
        else
        {
            { out_gpu.create(w * scale, (out_tile_y1 - out_tile_y0) * scale, channels, (size_t)4u, 1, blob_vkallocator); if(out_gpu.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
        }

        for (int xi = 0; xi < xtiles; xi++)
        {
            srTileCheckpoint();
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;

            int prepadding_right = prepadding;
            if (scale == 1)
            {
                prepadding_right += (tile_w_nopad + 3) / 4 * 4 - tile_w_nopad;
            }
            if (scale == 2)
            {
                prepadding_right += (tile_w_nopad + 1) / 2 * 2 - tile_w_nopad;
            }

            if (tta_mode)
            {
                // preproc
                ncnn::VkMat in_tile_gpu[8];
                ncnn::VkMat in_alpha_tile_gpu;
                {
                    // crop tile
                    int tile_x0 = xi * TILE_SIZE_X - prepadding;
                    int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding_right;
                    int tile_y0 = yi * TILE_SIZE_Y - prepadding;
                    int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding_bottom;

                    { in_tile_gpu[0].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[0].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[1].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[1].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[2].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[2].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[3].create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[3].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[4].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[4].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[5].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[5].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[6].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[6].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile_gpu[7].create(tile_y1 - tile_y0, tile_x1 - tile_x0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu[7].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }

                    if (channels == 4)
                    {
                        { in_alpha_tile_gpu.create(tile_w_nopad, tile_h_nopad, 1, in_out_tile_elemsize, 1, blob_vkallocator); if(in_alpha_tile_gpu.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    }

                    std::vector<ncnn::VkMat> bindings(10);
                    bindings[0] = in_gpu;
                    bindings[1] = in_tile_gpu[0];
                    bindings[2] = in_tile_gpu[1];
                    bindings[3] = in_tile_gpu[2];
                    bindings[4] = in_tile_gpu[3];
                    bindings[5] = in_tile_gpu[4];
                    bindings[6] = in_tile_gpu[5];
                    bindings[7] = in_tile_gpu[6];
                    bindings[8] = in_tile_gpu[7];
                    bindings[9] = in_alpha_tile_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = in_gpu.w;
                    constants[1].i = in_gpu.h;
                    constants[2].i = in_gpu.cstep;
                    constants[3].i = in_tile_gpu[0].w;
                    constants[4].i = in_tile_gpu[0].h;
                    constants[5].i = in_tile_gpu[0].cstep;
                    constants[6].i = prepadding;
                    constants[7].i = prepadding;
                    constants[8].i = xi * TILE_SIZE_X;
                    constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
                    constants[10].i = channels;
                    constants[11].i = in_alpha_tile_gpu.w;
                    constants[12].i = in_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = in_tile_gpu[0].w;
                    dispatcher.h = in_tile_gpu[0].h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(waifu2x_preproc, bindings, constants, dispatcher);
                }

                // waifu2x
                ncnn::VkMat out_tile_gpu[8];
                for (int ti = 0; ti < 8; ti++)
                {
                    ncnn::Extractor ex = net.create_extractor();

                    ex.set_blob_vkallocator(blob_vkallocator);
                    ex.set_workspace_vkallocator(blob_vkallocator);
                    ex.set_staging_vkallocator(staging_vkallocator);

                    srCheck(ex.input(input_blob.c_str(), in_tile_gpu[ti]), "network input/extract");

                    srCheck(ex.extract(output_blob.c_str(), out_tile_gpu[ti], cmd), "network input/extract");
                }

                ncnn::VkMat out_alpha_tile_gpu;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile_gpu = in_alpha_tile_gpu;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                }

                // postproc
                {
                    std::vector<ncnn::VkMat> bindings(10);
                    bindings[0] = out_tile_gpu[0];
                    bindings[1] = out_tile_gpu[1];
                    bindings[2] = out_tile_gpu[2];
                    bindings[3] = out_tile_gpu[3];
                    bindings[4] = out_tile_gpu[4];
                    bindings[5] = out_tile_gpu[5];
                    bindings[6] = out_tile_gpu[6];
                    bindings[7] = out_tile_gpu[7];
                    bindings[8] = out_alpha_tile_gpu;
                    bindings[9] = out_gpu;

                    std::vector<ncnn::vk_constant_type> constants(11);
                    constants[0].i = out_tile_gpu[0].w;
                    constants[1].i = out_tile_gpu[0].h;
                    constants[2].i = out_tile_gpu[0].cstep;
                    constants[3].i = out_gpu.w;
                    constants[4].i = out_gpu.h;
                    constants[5].i = out_gpu.cstep;
                    constants[6].i = xi * TILE_SIZE_X * scale;
                    constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    constants[8].i = channels;
                    constants[9].i = out_alpha_tile_gpu.w;
                    constants[10].i = out_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    dispatcher.h = out_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(waifu2x_postproc, bindings, constants, dispatcher);
                }
            }
            else
            {
                // preproc
                ncnn::VkMat in_tile_gpu;
                ncnn::VkMat in_alpha_tile_gpu;
                {
                    // crop tile
                    int tile_x0 = xi * TILE_SIZE_X - prepadding;
                    int tile_x1 = std::min((xi + 1) * TILE_SIZE_X, w) + prepadding_right;
                    int tile_y0 = yi * TILE_SIZE_Y - prepadding;
                    int tile_y1 = std::min((yi + 1) * TILE_SIZE_Y, h) + prepadding_bottom;

                    { in_tile_gpu.create(tile_x1 - tile_x0, tile_y1 - tile_y0, 3, in_out_tile_elemsize, 1, blob_vkallocator); if(in_tile_gpu.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }

                    if (channels == 4)
                    {
                        { in_alpha_tile_gpu.create(tile_w_nopad, tile_h_nopad, 1, in_out_tile_elemsize, 1, blob_vkallocator); if(in_alpha_tile_gpu.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    }

                    std::vector<ncnn::VkMat> bindings(3);
                    bindings[0] = in_gpu;
                    bindings[1] = in_tile_gpu;
                    bindings[2] = in_alpha_tile_gpu;

                    std::vector<ncnn::vk_constant_type> constants(13);
                    constants[0].i = in_gpu.w;
                    constants[1].i = in_gpu.h;
                    constants[2].i = in_gpu.cstep;
                    constants[3].i = in_tile_gpu.w;
                    constants[4].i = in_tile_gpu.h;
                    constants[5].i = in_tile_gpu.cstep;
                    constants[6].i = prepadding;
                    constants[7].i = prepadding;
                    constants[8].i = xi * TILE_SIZE_X;
                    constants[9].i = std::min(yi * TILE_SIZE_Y, prepadding);
                    constants[10].i = channels;
                    constants[11].i = in_alpha_tile_gpu.w;
                    constants[12].i = in_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = in_tile_gpu.w;
                    dispatcher.h = in_tile_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(waifu2x_preproc, bindings, constants, dispatcher);
                }

                // waifu2x
                ncnn::VkMat out_tile_gpu;
                {
                    ncnn::Extractor ex = net.create_extractor();

                    ex.set_blob_vkallocator(blob_vkallocator);
                    ex.set_workspace_vkallocator(blob_vkallocator);
                    ex.set_staging_vkallocator(staging_vkallocator);

                    srCheck(ex.input(input_blob.c_str(), in_tile_gpu), "network input/extract");

                    srCheck(ex.extract(output_blob.c_str(), out_tile_gpu, cmd), "network input/extract");
                }

                ncnn::VkMat out_alpha_tile_gpu;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile_gpu = in_alpha_tile_gpu;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile_gpu, out_alpha_tile_gpu, cmd, opt);
                    }
                }

                // postproc
                {
                    std::vector<ncnn::VkMat> bindings(3);
                    bindings[0] = out_tile_gpu;
                    bindings[1] = out_alpha_tile_gpu;
                    bindings[2] = out_gpu;

                    std::vector<ncnn::vk_constant_type> constants(11);
                    constants[0].i = out_tile_gpu.w;
                    constants[1].i = out_tile_gpu.h;
                    constants[2].i = out_tile_gpu.cstep;
                    constants[3].i = out_gpu.w;
                    constants[4].i = out_gpu.h;
                    constants[5].i = out_gpu.cstep;
                    constants[6].i = xi * TILE_SIZE_X * scale;
                    constants[7].i = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    constants[8].i = channels;
                    constants[9].i = out_alpha_tile_gpu.w;
                    constants[10].i = out_alpha_tile_gpu.h;

                    ncnn::VkMat dispatcher;
                    dispatcher.w = std::min(TILE_SIZE_X * scale, out_gpu.w - xi * TILE_SIZE_X * scale);
                    dispatcher.h = out_gpu.h;
                    dispatcher.c = channels;

                    cmd.record_pipeline(waifu2x_postproc, bindings, constants, dispatcher);
                }
            }

            if (xtiles > 1)
            {
                srCheck(cmd.submit_and_wait(), "Vulkan submit");
                cmd.reset();
            }
        }

        // download
        {
            ncnn::Mat out;

            if ((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage)
            {
                out = ncnn::Mat(out_gpu.w, out_gpu.h, (unsigned char*)outimage.data + (size_t)yi * scale * TILE_SIZE_Y * w * scale * channels, (size_t)channels, 1, opt.blob_allocator);
            }

            cmd.record_clone(out_gpu, out, opt);

            srCheck(cmd.submit_and_wait(), "Vulkan submit");

            if (!((opt.use_fp16_storage || opt.use_fp16_packed) && opt.use_int8_storage))
            {
                if (channels == 3)
                {
                    out.to_pixels((unsigned char*)outimage.data + (size_t)yi * scale * TILE_SIZE_Y * w * scale * channels, (bgr ? ncnn::Mat::PIXEL_RGB2BGR : ncnn::Mat::PIXEL_RGB));
                }
                if (channels == 4)
                {
                    out.to_pixels((unsigned char*)outimage.data + (size_t)yi * scale * TILE_SIZE_Y * w * scale * channels, (bgr ? ncnn::Mat::PIXEL_RGBA2BGRA : ncnn::Mat::PIXEL_RGBA));
                }
            }
        }
    }



    return 0;
}

int Waifu2x::process_cpu(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    if (noise == -1 && scale == 1)
    {
        outimage = inimage;
        return 0;
    }

    const unsigned char* pixeldata = (const unsigned char*)inimage.data;
    const int w = inimage.w;
    const int h = inimage.h;
    const int channels = inimage.elempack;

    const int TILE_SIZE_X = tilesize;
    const int TILE_SIZE_Y = tilesize;

    ncnn::Option opt = net.opt;

    // each tile 400x400
    const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
    const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

    for (int yi = 0; yi < ytiles; yi++)
    {
        const int tile_h_nopad = std::min((yi + 1) * TILE_SIZE_Y, h) - yi * TILE_SIZE_Y;

        int prepadding_bottom = prepadding;
        if (scale == 1)
        {
            prepadding_bottom += (tile_h_nopad + 3) / 4 * 4 - tile_h_nopad;
        }
        if (scale == 2)
        {
            prepadding_bottom += (tile_h_nopad + 1) / 2 * 2 - tile_h_nopad;
        }

        int in_tile_y0 = std::max(yi * TILE_SIZE_Y - prepadding, 0);
        int in_tile_y1 = std::min((yi + 1) * TILE_SIZE_Y + prepadding_bottom, h);

        for (int xi = 0; xi < xtiles; xi++)
        {
            srTileCheckpoint();
            const int tile_w_nopad = std::min((xi + 1) * TILE_SIZE_X, w) - xi * TILE_SIZE_X;

            int prepadding_right = prepadding;
            if (scale == 1)
            {
                prepadding_right += (tile_w_nopad + 3) / 4 * 4 - tile_w_nopad;
            }
            if (scale == 2)
            {
                prepadding_right += (tile_w_nopad + 1) / 2 * 2 - tile_w_nopad;
            }

            int in_tile_x0 = std::max(xi * TILE_SIZE_X - prepadding, 0);
            int in_tile_x1 = std::min((xi + 1) * TILE_SIZE_X + prepadding_right, w);

            // crop tile
            ncnn::Mat in, in_nopad;
            {
                if (channels == 3)
                {
                    in = ncnn::Mat::from_pixels_roi(pixeldata, (bgr ? ncnn::Mat::PIXEL_BGR2RGB : ncnn::Mat::PIXEL_RGB), w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
                    if(in.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
                }
                if (channels == 4)
                {
#if _WIN32
                    in_nopad = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_BGRA2RGBA, w, h, xi * TILE_SIZE_X, yi * TILE_SIZE_Y, tile_w_nopad, tile_h_nopad);
                    if(in_nopad.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_BGRA2RGBA, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
                    if(in.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
#else
                    in_nopad = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_RGBA, w, h, xi * TILE_SIZE_X, yi * TILE_SIZE_Y, tile_w_nopad, tile_h_nopad);
                    if(in_nopad.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
                    in = ncnn::Mat::from_pixels_roi(pixeldata, ncnn::Mat::PIXEL_RGBA, w, h, in_tile_x0, in_tile_y0, in_tile_x1 - in_tile_x0, in_tile_y1 - in_tile_y0);
                    if(in.empty()) throw SrNativeError(-100,"input strip allocation failed; lower input MP threshold");
#endif
                }
            }

            ncnn::Mat out;

            if (tta_mode)
            {
                // split alpha and preproc
                ncnn::Mat in_tile[8];
                ncnn::Mat in_alpha_tile;
                {
                    { in_tile[0].create(in.w, in.h, 3); if(in_tile[0].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    for (int q = 0; q < 3; q++)
                    {
                        const float* ptr = in.channel(q);
                        float* outptr0 = in_tile[0].channel(q);

                        for (int i = 0; i < in.h; i++)
                        {
                            for (int j = 0; j < in.w; j++)
                            {
                                *outptr0++ = *ptr++ * (1 / 255.f);
                            }
                        }
                    }

                    if (channels == 4)
                    {
                        in_alpha_tile = in_nopad.channel_range(3, 1).clone();
                    }
                }

                // border padding
                {
                    int pad_top = std::max(prepadding - yi * TILE_SIZE_Y, 0);
                    int pad_bottom = std::max(std::min((yi + 1) * TILE_SIZE_Y + prepadding_bottom - h, prepadding_bottom), 0);
                    int pad_left = std::max(prepadding - xi * TILE_SIZE_X, 0);
                    int pad_right = std::max(std::min((xi + 1) * TILE_SIZE_X + prepadding_right - w, prepadding_right), 0);

                    ncnn::Mat in_tile_padded;
                    ncnn::copy_make_border(in_tile[0], in_tile_padded, pad_top, pad_bottom, pad_left, pad_right, ncnn::BORDER_REPLICATE, 0.f, net.opt);
                    in_tile[0] = in_tile_padded;
                }

                // the other 7 directions
                {
                    { in_tile[1].create(in_tile[0].w, in_tile[0].h, 3); if(in_tile[1].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[2].create(in_tile[0].w, in_tile[0].h, 3); if(in_tile[2].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[3].create(in_tile[0].w, in_tile[0].h, 3); if(in_tile[3].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[4].create(in_tile[0].h, in_tile[0].w, 3); if(in_tile[4].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[5].create(in_tile[0].h, in_tile[0].w, 3); if(in_tile[5].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[6].create(in_tile[0].h, in_tile[0].w, 3); if(in_tile[6].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    { in_tile[7].create(in_tile[0].h, in_tile[0].w, 3); if(in_tile[7].empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }

                    for (int q = 0; q < 3; q++)
                    {
                        const ncnn::Mat in_tile_0 = in_tile[0].channel(q);
                        ncnn::Mat in_tile_1 = in_tile[1].channel(q);
                        ncnn::Mat in_tile_2 = in_tile[2].channel(q);
                        ncnn::Mat in_tile_3 = in_tile[3].channel(q);
                        ncnn::Mat in_tile_4 = in_tile[4].channel(q);
                        ncnn::Mat in_tile_5 = in_tile[5].channel(q);
                        ncnn::Mat in_tile_6 = in_tile[6].channel(q);
                        ncnn::Mat in_tile_7 = in_tile[7].channel(q);

                        for (int i = 0; i < in_tile[0].h; i++)
                        {
                            const float* outptr0 = in_tile_0.row(i);
                            float* outptr1 = in_tile_1.row(in_tile[0].h - 1 - i);
                            float* outptr2 = in_tile_2.row(i) + in_tile[0].w - 1;
                            float* outptr3 = in_tile_3.row(in_tile[0].h - 1 - i) + in_tile[0].w - 1;

                            for (int j = 0; j < in_tile[0].w; j++)
                            {
                                float* outptr4 = in_tile_4.row(j) + i;
                                float* outptr5 = in_tile_5.row(in_tile[0].w - 1 - j) + i;
                                float* outptr6 = in_tile_6.row(j) + in_tile[0].h - 1 - i;
                                float* outptr7 = in_tile_7.row(in_tile[0].w - 1 - j) + in_tile[0].h - 1 - i;

                                float v = *outptr0++;

                                *outptr1++ = v;
                                *outptr2-- = v;
                                *outptr3-- = v;
                                *outptr4 = v;
                                *outptr5 = v;
                                *outptr6 = v;
                                *outptr7 = v;
                            }
                        }
                    }
                }

                // waifu2x
                ncnn::Mat out_tile[8];
                for (int ti = 0; ti < 8; ti++)
                {
                    ncnn::Extractor ex = net.create_extractor();

                    srCheck(ex.input(input_blob.c_str(), in_tile[ti]), "network input/extract");

                    srCheck(ex.extract(output_blob.c_str(), out_tile[ti]), "network input/extract");
                }

                ncnn::Mat out_alpha_tile;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile = in_alpha_tile;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                }

                // postproc and merge alpha
                {
                    { out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels); if(out.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    for (int q = 0; q < 3; q++)
                    {
                        const ncnn::Mat out_tile_0 = out_tile[0].channel(q);
                        const ncnn::Mat out_tile_1 = out_tile[1].channel(q);
                        const ncnn::Mat out_tile_2 = out_tile[2].channel(q);
                        const ncnn::Mat out_tile_3 = out_tile[3].channel(q);
                        const ncnn::Mat out_tile_4 = out_tile[4].channel(q);
                        const ncnn::Mat out_tile_5 = out_tile[5].channel(q);
                        const ncnn::Mat out_tile_6 = out_tile[6].channel(q);
                        const ncnn::Mat out_tile_7 = out_tile[7].channel(q);
                        float* outptr = out.channel(q);

                        for (int i = 0; i < out.h; i++)
                        {
                            const float* ptr0 = out_tile_0.row(i);
                            const float* ptr1 = out_tile_1.row(out_tile[0].h - 1 - i);
                            const float* ptr2 = out_tile_2.row(i) + out_tile[0].w - 1;
                            const float* ptr3 = out_tile_3.row(out_tile[0].h - 1 - i) + out_tile[0].w - 1;

                            for (int j = 0; j < out.w; j++)
                            {
                                const float* ptr4 = out_tile_4.row(j) + i;
                                const float* ptr5 = out_tile_5.row(out_tile[0].w - 1 - j) + i;
                                const float* ptr6 = out_tile_6.row(j) + out_tile[0].h - 1 - i;
                                const float* ptr7 = out_tile_7.row(out_tile[0].w - 1 - j) + out_tile[0].h - 1 - i;

                                float v = (*ptr0++ + *ptr1++ + *ptr2-- + *ptr3-- + *ptr4 + *ptr5 + *ptr6 + *ptr7) / 8;

                                *outptr++ = v * 255.f + 0.5f;
                            }
                        }
                    }

                    if (channels == 4)
                    {
                        memcpy(out.channel_range(3, 1), out_alpha_tile, out_alpha_tile.total() * sizeof(float));
                    }
                }
            }
            else
            {
                // split alpha and preproc
                ncnn::Mat in_tile;
                ncnn::Mat in_alpha_tile;
                {
                    { in_tile.create(in.w, in.h, 3); if(in_tile.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    for (int q = 0; q < 3; q++)
                    {
                        const float* ptr = in.channel(q);
                        float* outptr = in_tile.channel(q);

                        for (int i = 0; i < in.w * in.h; i++)
                        {
                            *outptr++ = *ptr++ * (1 / 255.f);
                        }
                    }

                    if (channels == 4)
                    {
                        in_alpha_tile = in_nopad.channel_range(3, 1).clone();
                    }
                }

                // border padding
                {
                    int pad_top = std::max(prepadding - yi * TILE_SIZE_Y, 0);
                    int pad_bottom = std::max(std::min((yi + 1) * TILE_SIZE_Y + prepadding_bottom - h, prepadding_bottom), 0);
                    int pad_left = std::max(prepadding - xi * TILE_SIZE_X, 0);
                    int pad_right = std::max(std::min((xi + 1) * TILE_SIZE_X + prepadding_right - w, prepadding_right), 0);

                    ncnn::Mat in_tile_padded;
                    ncnn::copy_make_border(in_tile, in_tile_padded, pad_top, pad_bottom, pad_left, pad_right, ncnn::BORDER_REPLICATE, 0.f, net.opt);
                    in_tile = in_tile_padded;
                }

                // waifu2x
                ncnn::Mat out_tile;
                {
                    ncnn::Extractor ex = net.create_extractor();

                    srCheck(ex.input(input_blob.c_str(), in_tile), "network input/extract");

                    srCheck(ex.extract(output_blob.c_str(), out_tile), "network input/extract");
                }

                ncnn::Mat out_alpha_tile;
                if (channels == 4)
                {
                    if (scale == 1)
                    {
                        out_alpha_tile = in_alpha_tile;
                    }
                    if (scale == 2)
                    {
                        bicubic_2x->forward(in_alpha_tile, out_alpha_tile, opt);
                    }
                }

                // postproc and merge alpha
                {
                    { out.create(tile_w_nopad * scale, tile_h_nopad * scale, channels); if(out.empty()) throw SrNativeError(-100,"tile/strip allocation failed; reduce tile size or input MP threshold"); }
                    for (int q = 0; q < 3; q++)
                    {
                        float* outptr = out.channel(q);

                        for (int i = 0; i < out.h; i++)
                        {
                            const float* ptr = out_tile.channel(q).row(i);

                            for (int j = 0; j < out.w; j++)
                            {
                                *outptr++ = *ptr++ * 255.f + 0.5f;
                            }
                        }
                    }

                    if (channels == 4)
                    {
                        memcpy(out.channel_range(3, 1), out_alpha_tile, out_alpha_tile.total() * sizeof(float));
                    }
                }
            }

            {
                if (channels == 3)
                {
                    out.to_pixels((unsigned char*)outimage.data + (size_t)yi * scale * TILE_SIZE_Y * w * scale * channels + xi * scale * TILE_SIZE_X * channels, (bgr ? ncnn::Mat::PIXEL_RGB2BGR : ncnn::Mat::PIXEL_RGB), w * scale * channels);
                }
                if (channels == 4)
                {
                    out.to_pixels((unsigned char*)outimage.data + (size_t)yi * scale * TILE_SIZE_Y * w * scale * channels + xi * scale * TILE_SIZE_X * channels, (bgr ? ncnn::Mat::PIXEL_RGBA2BGRA : ncnn::Mat::PIXEL_RGBA), w * scale * channels);
                }
            }
        }
    }

    return 0;
}

// Same-padding models retain the real neighbouring pixels. Image boundaries are
// left to the model; only the core region is copied into the final RGB image.
int Waifu2x::process_same(const ncnn::Mat& inimage, ncnn::Mat& outimage) const
{
    const int w = inimage.w, h = inimage.h;
    const int tile = std::max(32, tilesize);
    for (int y = 0; y < h; y += tile) {
        for (int x = 0; x < w; x += tile) {
            srTileCheckpoint();
            const int cw = std::min(tile, w-x), ch = std::min(tile, h-y);
            const int left = std::max(0, x-prepadding), top = std::max(0, y-prepadding);
            const int right = std::min(w, x+cw+prepadding), bottom = std::min(h, y+ch+prepadding);
            const int rw = right-left, rh = bottom-top;
            ncnn::Mat input = ncnn::Mat::from_pixels_roi(
                (const unsigned char*)inimage.data,
                bgr ? ncnn::Mat::PIXEL_RGB2BGR : ncnn::Mat::PIXEL_RGB,
                w, h, w*3, left, top, rw, rh);
            if (input.empty()) throw SrNativeError(-100, "input tile allocation failed");
            const float norm[3] = {1.f/255.f, 1.f/255.f, 1.f/255.f};
            input.substract_mean_normalize(nullptr, norm);
            auto ex = net.create_extractor();
            srCheck(ex.input(input_blob.c_str(), input), "generic input");
            ncnn::Mat output;
            srCheck(ex.extract(output_blob.c_str(), output), "generic extract");
            if (output.empty() || output.w != rw*scale || output.h != rh*scale || output.c != 3 || output.elembits()!=32)
                throw SrNativeError(-201, "generic output shape/type does not match model contract");
            const int ox = (x-left)*scale, oy = (y-top)*scale;
            auto* dst = (unsigned char*)outimage.data;
            for (int yy=0; yy<ch*scale; ++yy) {
                for (int xx=0; xx<cw*scale; ++xx) {
                    for (int c=0; c<3; ++c) {
                        const float v = output.channel(bgr ? 2-c : c).row(oy+yy)[ox+xx];
                        if (!std::isfinite(v)) throw SrNativeError(-201, "non-finite model output");
                        dst[((size_t)(y*scale+yy)*w*scale+x*scale+xx)*3+c] =
                            (unsigned char)std::max(0.f, std::min(255.f, v*255.f+0.5f));
                    }
                }
            }
        }
    }
    return 0;
}
