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

        // 按钮条位置 → 用**视图自己的几何**算出按钮位置，点它必须命中。
        // ⚠️ 按钮个数跟着"有没有译文"变（用户口径：还没翻过时只有一颗「翻译」），
        // 所以这里必须用同一个判据构造期望几何 —— 否则就成了"按另一套布局点按钮"。
        val cw = style.contentWidthPx(600)
        val size = NovelImageMetrics.fit(image, cw, style.contentHeightPx(900))!!
        val left = style.paddingPx + (cw - size.width) / 2f
        val top = style.topPaddingPx
        val density = ctx.resources.displayMetrics.density
        val withPayload = NovelImageControls.layout(
            imageLeft = left, imageTop = top, imageRight = left + size.width, imageBottom = top + size.height,
            density = density, buttons = NovelImageControls.buttonsFor(true),
            boundsLeft = style.paddingPx, boundsTop = style.topPaddingPx,
            boundsRight = style.paddingPx + cw, boundsBottom = 900f - style.bottomPaddingPx,
        )
        assertEquals(
            "有译文时点最左那颗 = 重新翻译",
            NovelImageButton.TRANSLATE,
            view.imageControlAt(withPayload[0].centerX, withPayload[0].centerY),
        )
        assertEquals(
            "点中间那颗 = 三态",
            NovelImageButton.TOGGLE,
            view.imageControlAt(withPayload[1].centerX, withPayload[1].centerY),
        )
        assertEquals(
            "点最右那颗 = 删除",
            NovelImageButton.DELETE,
            view.imageControlAt(withPayload[2].centerX, withPayload[2].centerY),
        )
        assertNull("图片中间不是按钮", view.imageControlAt(left + size.width / 2f, top + size.height / 2f))
    }

    /**
     * **还没翻过的图只有一颗「翻译」按钮**（用户口径 2026-10-04：「长按出现翻译按钮，
     * 点击翻译才会 OCR 翻译」）。
     *
     * 那两颗"没什么可做"的按钮（三态 / 删除）不能出现，也不能留下命中区 ——
     * 点了没反应比没有按钮更让人困惑。
     */
    @Test
    fun `没译文时只有翻译一颗可点`() {
        val ctx = RuntimeEnvironment.getApplication()
        val image = NovelImage(pngFile(200, 300), 200, 300)
        val content = contentWithImage(image, withPayload = false)
        val page = pageWithImage(content)

        val view = NovelPageView(ctx)
        view.bind(content, page, style, Color.BLACK)
        view.setImageState(emptyMap(), focused = 1, translations = emptyMap())
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 600, 900)
        view.draw(Canvas(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)))

        val cw = style.contentWidthPx(600)
        val size = NovelImageMetrics.fit(image, cw, style.contentHeightPx(900))!!
        val left = style.paddingPx + (cw - size.width) / 2f
        val top = style.topPaddingPx
        val density = ctx.resources.displayMetrics.density
        val one = NovelImageControls.layout(
            imageLeft = left, imageTop = top, imageRight = left + size.width, imageBottom = top + size.height,
            density = density, buttons = NovelImageControls.buttonsFor(false),
            boundsLeft = style.paddingPx, boundsTop = style.topPaddingPx,
            boundsRight = style.paddingPx + cw, boundsBottom = 900f - style.bottomPaddingPx,
        )
        assertEquals(1, one.size)
        assertEquals(
            "那颗按钮必须命中「翻译」",
            NovelImageButton.TRANSLATE,
            view.imageControlAt(one[0].centerX, one[0].centerY),
        )
        // 三颗布局里 TOGGLE 会占的位置（单颗布局里是空白）不该有命中区。
        // ⚠️ 不能拿最右那颗去断言 —— 单颗按钮也是按同一套规则摆的，位置可能重合。
        val three = NovelImageControls.layout(
            imageLeft = left, imageTop = top, imageRight = left + size.width, imageBottom = top + size.height,
            density = density, buttons = NovelImageControls.buttonsFor(true),
            boundsLeft = style.paddingPx, boundsTop = style.topPaddingPx,
            boundsRight = style.paddingPx + cw, boundsBottom = 900f - style.bottomPaddingPx,
        )
        assertNull("没译文时不该有三态按钮的命中区", view.imageControlAt(three[1].centerX, three[1].centerY))
        assertNull("没译文时左边那两颗的位置都该是空白", view.imageControlAt(three[0].centerX, three[0].centerY))
        // ⚠️ 不能拿 `three[2]`（最右那颗）去断言：整条是**贴图片右边界**摆的，
        // 单颗布局那颗与三颗布局的 DELETE 位置重合，那里本来就该命中「翻译」。
    }

    /**
     * **很小的图**（电子书里到处都是的占位小图）：按钮不缩，而是落到**图外**，仍然可点。
     *
     * 用户口径：「要考虑到多种图片尺寸，翻译按钮可能因为图片尺寸太小没法放在图片上面」。
     * 这条同时钉住：① 画得出来不崩；② 按钮尺寸不变；③ 按钮在图外但**仍在正文框内**且可命中；
     * ④ 选中框把图片**包住**（外扩），各种尺寸都看得见"已选中"。
     */
    @Test
    fun `极小的图按钮落到图外且选中框包住图片`() {
        val ctx = RuntimeEnvironment.getApplication()
        val image = NovelImage(pngFile(20, 20), 20, 20)
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
        view.draw(Canvas(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)))

        val controls = view.controlsOf()!!
        // 选中框要把图片包进去（上/下/左/右都外扩）
        val cw2 = style.contentWidthPx(600)
        val size2 = NovelImageMetrics.fit(image, cw2, style.contentHeightPx(900))!!
        val imgLeft = style.paddingPx + (cw2 - size2.width) / 2f
        assertTrue("选中框左边界要在图片左侧", controls.selection.left < imgLeft)
        assertTrue("选中框右边界要在图片右侧", controls.selection.right > imgLeft + size2.width)
        assertTrue("选中框上边界要在图片上方", controls.selection.top <= style.topPaddingPx)
        assertEquals(
            "未聚焦时不该有按钮（这条是聚焦态，所以三颗都在）",
            3,
            controls.items.size,
        )
        val density = ctx.resources.displayMetrics.density
        for (it in controls.items) {
            assertEquals(
                "按钮不因图小而缩水",
                NovelImageControls.BUTTON_DP * density,
                it.size,
                0.01f,
            )
            assertTrue("按钮不能画到屏幕外", it.left >= 0f && it.right <= 600f && it.bottom <= 900f)
        }
        assertEquals(
            "图外的按钮同样点得到",
            NovelImageButton.TRANSLATE,
            view.imageControlAt(controls.items[0].centerX, controls.items[0].centerY),
        )
        assertEquals(
            NovelImageButton.DELETE,
            view.imageControlAt(controls.items[2].centerX, controls.items[2].centerY),
        )
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
