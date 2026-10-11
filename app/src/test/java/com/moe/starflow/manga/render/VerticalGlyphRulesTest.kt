package com.moe.starflow.manga.render

import org.junit.Assert.*
import org.junit.Test

class VerticalGlyphRulesTest {
    @Test fun dedicatedVerticalFormsCoverBracketFamiliesAndEllipses() {
        val pairs = mapOf('(' to '︵', '）' to '︶', '[' to '﹇', '}' to '︸', '【' to '︻',
            '〖' to '︗', '〔' to '︹', '〈' to '︿', '》' to '︾', '「' to '﹁', '』' to '﹄',
            '｢' to '﹁', '…' to '︙', '‥' to '︰', '—' to '︱', '＿' to '︳', '、' to '︑')
        pairs.forEach { (source, target) ->
            val glyph = VerticalGlyphRules.resolve(source) { true }
            assertEquals(target.toString(), glyph.text)
            assertEquals(0f, glyph.rotation, 0f)
            assertTrue(glyph.fitToCell)
        }
    }

    @Test fun absentVerticalGlyphRotatesOriginalShapeInsteadOfDrawingTofu() {
        "()（）[]{}〔〕【】〖〗〈〉《》「」『』…‥—–_".forEach {
            val glyph = VerticalGlyphRules.resolve(it) { false }
            assertEquals(it.toString(), glyph.text)
            assertEquals(90f, glyph.rotation, 0f)
        }
    }

    @Test fun otherQuotesDashesWavesAndProlongedSoundsRotateClockwise() {
        "〘〙〚〛“”‘’〝〞〟«»‹›‐‑‒―-－~～〜〰ーｰ゠".forEach {
            assertEquals(90f, VerticalGlyphRules.resolve(it) { true }.rotation, 0f)
        }
    }

    @Test fun commasAndStopsUsePositionFallbackRatherThanRotation() {
        ",，、。．.､｡".forEach {
            val glyph = VerticalGlyphRules.resolve(it) { false }
            assertTrue(glyph.corner)
            assertEquals(0f, glyph.rotation, 0f)
        }
    }

    @Test fun uprightAndAlreadyVerticalCharactersAreNeverRotated() {
        "!?！？‼⁇⁈⁉・中文あいうABC123︵︶﹁﹂︙︰︓︖".forEach {
            val glyph = VerticalGlyphRules.resolve(it) { false }
            assertEquals(it.toString(), glyph.text)
            assertEquals(0f, glyph.rotation, 0f)
            assertFalse(glyph.corner)
        }
    }
}
