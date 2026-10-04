package com.moe.starflow.novel.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.moe.starflow.novel.translate.NovelImageTextBlock
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **绘制 + 命中**的整链路守卫（Robolectric）：真实 `NovelPageView` + 真实 PNG + 真实覆盖层渲染。
 *
 * 覆盖的是"单测盖不住"的那一段：分页给插图留的高度 → 视图画出覆盖层/按钮条 →
 * 手指点在按钮上能命中。几何错位的表现都是**静默**的（图偏了、按钮点不动），
 * 而模块文档写明 Robolectric 的文本引擎是桩，所以这里刻意只断言"走通了 / 命中了"，
 * 不断言像素。
 *
 * ⚠️ 覆盖层渲染走的是漫画那套 `OverlayRenderer`（它自己有 `OverlayRendererTest`），
 * 这里只保证"在小说的插图路径上真的调得通、不抛"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPageViewImageTest {

    @get:Rule val tmp = TemporaryFolder()

    private val style = NovelTextStyle(
        fontSizePx = 40f,
        lineSpacingMultiplier = 1.5f,
        paragraphSpacingPx = 16f,
        paddingPx = 20f,
        topPaddingPx = 0f,
        bottomPaddingPx = 0f,
    )

    private fun pngFile(w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return File(tmp.root, "img.png").apply { writeBytes(out.toByteArray()) }
    }

    private fun payload(): NovelImageTranslation = NovelImageTranslation(
        sourceLang = "ja", targetLang = "zh", ocrEngine = "ppocrv6",
        blocks = listOf(
            // ⚠️ 坐标是**原图**像素（200x300 那张图内），不是"OCR 采样位图"的像素 —— 见
            // `NovelImagePipeline.toBlocks` 与 `NovelImageOverlay.bubblesOf` 的缩放假
            NovelImageTextBlock(20, 30, 120, 90, "こんにちは", "你好", vertical = false, fontSize = 20f),
            NovelImageTextBlock(140, 40, 180, 260, "第一話", "第一话", vertical = true, fontSize = 22f),
        ),
    )

    /** 造一页**只有插图**的章内容（这样插图就是页首，几何好算）。 */
    private fun contentWithImage(image: NovelImage, withPayload: Boolean): ChapterContent {
        val paras = listOf(
            NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片]"),
        )
        val pages = NovelPaginator.paginate(
            paras, style, 600, 900, null, null, mapOf(1 to image),
        )
        return ChapterContent(
            chapterIndex = 0,
            title = "t",
            paragraphs = paras,
            displayTexts = paras.associate { it.index to it.originalText },
            pages = pages,
            images = mapOf(1 to image),
            imageTranslations = if (withPayload) mapOf(1 to payload()) else emptyMap(),
        )
    }

    private fun pageWithImage(c: ChapterContent): NovelPage =
        c.pages.first { page -> page.segments.any { it.paraIndex == 1 } }

    /** 聚焦插图时：覆盖层画得出来（不抛），且按钮条的命中与视图内部几何一致。 */
    @Test
    fun `聚焦插图时画覆盖层且按钮可命中`() {
        val ctx = RuntimeEnvironment.getApplication()
        val image = NovelImage(pngFile(200, 300), 200, 300)
        val content = contentWithImage(image, withPayload = true)
        val page = pageWithImage(content)

        val view = NovelPageView(ctx)
        view.bind(content, page, style, Color.BLACK)
        view.setImageState(
            mapOf(1 to NovelImageVisual.TRANSLATED), focused = 1, translations = content.imageTranslations,
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 600, 900)

        // 真的画一遍（覆盖层渲染 + 按钮条都在这条路上）
        val canvasBitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(canvasBitmap))

        // 按钮条贴图片右下角 → 用同一份几何算出三态按钮的中心，点它必须命中
        val cw = style.contentWidthPx(600)
        val size = NovelImageMetrics.fit(image, cw, style.contentHeightPx(900))!!
        val left = style.paddingPx + (cw - size.width) / 2f
        val top = page.segments.first { it.paraIndex == 1 }.let { _ ->
            // 插图是这一页的第一段（分页把它单独成页）→ 正文框顶部
            style.topPaddingPx
        }
        val density = ctx.resources.displayMetrics.density
        val items = NovelImageControls.layout(left + size.width, top + size.height, density)
        assertEquals(
            "点三态按钮必须命中三态",
            NovelImageButton.TOGGLE,
            view.imageControlAt(items[0].centerX, items[0].centerY),
        )
        assertEquals(
            "点删除按钮必须命中删除",
            NovelImageButton.DELETE,
            view.imageControlAt(items[2].centerX, items[2].centerY),
        )
        assertNull("图片中间不是按钮", view.imageControlAt(left + size.width / 2f, top + size.height / 2f))
    }

    /** 没有译文载荷时也能画（原图态），且**不显示**按钮条（没聚焦）。 */
    @Test
    fun `没载荷也能画且未聚焦时没有按钮`() {
        val ctx = RuntimeEnvironment.getApplication()
        val image = NovelImage(pngFile(120, 160), 120, 160)
        val content = contentWithImage(image, withPayload = false)
        val page = pageWithImage(content)

        val view = NovelPageView(ctx)
        view.bind(content, page, style, Color.BLACK)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 600, 900)
        view.draw(Canvas(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)))

        assertNull("没聚焦时按钮条不该出现（也不该命中）", view.imageControlAt(580f, 880f))
    }

    /** **纯图态不画覆盖层**（三态里唯一"什么都不叠"的那一态）。 */
    @Test
    fun `纯图态不渲染覆盖层`() {
        val ctx = RuntimeEnvironment.getApplication()
        val image = NovelImage(pngFile(200, 300), 200, 300)
        assertNull(
            "纯图态要直接返回原图（null = 调用方画底图）",
            NovelImageOverlay.of(ctx, image, payload(), NovelImageVisual.PLAIN, 200),
        )
        assertTrue(
            "译文态要真的渲染出覆盖层",
            NovelImageOverlay.of(ctx, image, payload(), NovelImageVisual.TRANSLATED, 200) != null,
        )
    }

    /**
     * **坐标必须按底图倍率缩回去**：载荷的矩形是原图像素，而底图常常是采样解码的
     * （`NovelImageStore.bitmapOf(image, 显示宽度)`）。
     *
     * 不缩的后果不是"偏几像素"，而是**整体偏移 + 大半画在图外**（原图 2000 宽、底图 900 时
     * 右侧的译文全跑到图外）。这条用"缩完仍在底图内 / 不缩就出界"直接钉住。
     */
    @Test
    fun `载荷坐标按底图倍率缩放且夹在底图内`() {
        val payload = NovelImageTranslation(
            "ja", "zh", "ppocrv6",
            listOf(NovelImageTextBlock(1000, 100, 1200, 200, "あ", "啊", fontSize = 40f)),
        )
        // 原图 2000 宽、底图 1000 宽 → 倍率 0.5：矩形应落在 500..600
        val scaled = NovelImageOverlay.bubblesOf(payload, useOriginal = false, scale = 0.5f, boundsW = 1000, boundsH = 1000)
        assertEquals(1, scaled.size)
        assertEquals(500, scaled[0].rect.left)
        assertEquals(600, scaled[0].rect.right)
        assertEquals("字号也要跟着缩", 20f, scaled[0].fontSize, 0.01f)

        // 越界坐标（历史/手改过的载荷）必须被夹进底图，而不是把渲染器撑爆
        val wild = NovelImageTranslation(
            "ja", "zh", "ppocrv6",
            listOf(NovelImageTextBlock(5000, 5000, 9000, 9000, "あ", "啊")),
        )
        val clamped = NovelImageOverlay.bubblesOf(wild, useOriginal = false, scale = 1f, boundsW = 500, boundsH = 400)
        assertEquals(1, clamped.size)
        assertTrue("左边界要夹进底图", clamped[0].rect.left in 0..499)
        assertTrue("右边界要夹进底图", clamped[0].rect.right in 1..500)
        assertTrue("下边界要夹进底图", clamped[0].rect.bottom in 1..400)
    }
}
