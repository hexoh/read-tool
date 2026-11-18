package com.readtool.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rect() = CharRect(0f, 0f, 10f, 10f)

private fun page(vararg chars: String, vertical: Boolean = true, axis: String = "v"): PageModel {
    val blocks = listOf(Block("body", "body", axis))
    val nodes = chars.mapIndexed { i, c ->
        CharNode(i, c, "body", col = 0, row = i, box = rect())
    }
    return PageModel(vertical, blocks, nodes).also { it.buildLines() }
}

private val readings = Readings(
    words = listOf(
        WordEntry("般若", "bō rě", "bān ruò", "梵 prajñā"),
        WordEntry("涅槃", "niè pán", "niè pán", "梵 nirvāṇa"),
    ),
    chars = mapOf(
        "般" to CharDiff(Variant("bō", "ㄅㄛ"), Variant("bān", "ㄅㄢ"), "般若读 bō"),
    ),
    polyphones = emptyMap(),
)

private val lexicon = Lexicon(
    mapOf(
        "般" to CharInfo(p = "bān", z = "ㄅㄢ", zy = mapOf("bō" to "ㄅㄛ", "bān" to "ㄅㄢ", "ruò" to "ㄖㄨㄛˋ")),
        "若" to CharInfo(p = "ruò", z = "ㄖㄨㄛˋ", zy = mapOf("rě" to "ㄖㄜˇ", "ruò" to "ㄖㄨㄛˋ")),
        "涅" to CharInfo(p = "niè", z = "ㄋㄧㄝˋ", zy = mapOf("niè" to "ㄋㄧㄝˋ")),
        "槃" to CharInfo(p = "pán", z = "ㄆㄢˊ", zy = mapOf("pán" to "ㄆㄢˊ")),
        "云" to CharInfo(p = "yún", z = "ㄩㄣˊ"),
    ),
)

private val resolver = ReadingResolver(readings, lexicon)

class ReadingResolverTest {
    @Test
    fun `词组语境优先，普通页也按旧读`() {
        val p = page("般", "若", "涅", "槃")
        val r = resolver.resolve(p, p.chars[0], "general")
        assertEquals(MatchType.PHRASE, r.matchType)
        assertEquals("般若", r.phrase)
        assertEquals("old", r.variant)
        assertEquals("bō rě", r.p)
        assertEquals("ㄅㄛ", r.z)
    }

    @Test
    fun `双音并列，不隐藏读音`() {
        val p = page("般", "若")
        val r = resolver.resolve(p, p.chars[1], "general")
        assertEquals(listOf("bō rě", "bān ruò"), r.readings.map { it.p })
        assertEquals("旧读", r.readings[0].label)
        assertEquals("今读", r.readings[1].label)
    }

    @Test
    fun `无词条时普通页回落今读`() {
        val p = page("般")
        val r = resolver.resolve(p, p.chars[0], "general")
        assertEquals(MatchType.CHAR, r.matchType)
        assertEquals("modern", r.variant)
        assertEquals("bān", r.p)
    }

    @Test
    fun `诵读页单字差异按旧读`() {
        val p = page("般")
        val r = resolver.resolve(p, p.chars[0], "scripture")
        assertEquals("old", r.variant)
        assertEquals("bō", r.p)
    }

    @Test
    fun `用户纠正优先级最高`() {
        val p = page("般", "若")
        val r = resolver.resolve(p, p.chars[0], "scripture", mapOf("般" to "modern"))
        assertEquals(MatchType.OVERRIDE, r.matchType)
        assertEquals("bān ruò", r.p)
    }

    @Test
    fun `无差异词条不产生双音`() {
        val p = page("涅", "槃")
        val r = resolver.resolve(p, p.chars[0], "general")
        assertEquals(1, r.readings.size)
        assertEquals("niè pán", r.p)
    }

    @Test
    fun `字库未收用兜底读音并标记 approx`() {
        val p = page("味")
        val local = ReadingResolver(readings, lexicon, mapOf("味" to "wèi"))
        val r = local.resolve(p, p.chars[0], "general")
        assertEquals("wèi", r.p)
        assertTrue(r.approx)
        assertEquals(MatchType.PLAIN, r.matchType)
    }

    @Test
    fun `既无字库也无兜底时不编造读音`() {
        val p = page("𠀀")
        val r = resolver.resolve(p, p.chars[0], "general")
        assertNull(r.p)
        assertEquals(MatchType.UNKNOWN, r.matchType)
    }
}

class ReadingOrderTest {
    @Test
    fun `竖排列号升序等于右起向左，页眉单独成行`() {
        val p = PageModel(
            vertical = true,
            blocks = listOf(Block("body", "body", "v"), Block("header", "header_footer", "h")),
            chars = listOf(
                CharNode(0, "般", "body", 0, 0, rect()),
                CharNode(1, "若", "body", 0, 1, rect()),
                CharNode(2, "涅", "body", 1, 0, rect()),
                CharNode(3, "识", "header", 0, 0, rect()),
                CharNode(4, "字", "header", 1, 0, rect()),
            ),
        )
        p.buildLines()
        val body = p.lines.filter { it.blockId == "body" }.map { it.text }
        assertEquals(listOf("般若", "涅"), body)
        assertEquals(listOf("识字"), p.lines.filter { it.blockId == "header" }.map { it.text })
    }

    @Test
    fun `朗读顺序跳过注文与标点`() {
        val p = PageModel(
            vertical = true,
            blocks = listOf(Block("body", "body", "v"), Block("note", "interlinear_note", "v", skipWhenReading = true)),
            chars = listOf(
                CharNode(0, "般", "body", 0, 0, rect()),
                CharNode(1, "若", "body", 0, 1, rect()),
                CharNode(2, "注", "note", 0, 0, rect(), note = true),
            ),
        )
        p.buildLines()
        assertEquals(listOf("般", "若"), p.readingOrder().map { it.c })
        assertEquals(3, p.readingOrder(skipNotes = false).size)
    }
}

class ContextJudgeTest {
    private val terms = ContextTerms(
        ritual = listOf("般若", "涅槃", "南无"),
        formulas = listOf("如是我闻"),
        negative = listOf("版权所有", "定价"),
        modernMarkers = listOf("这个", "可以"),
    )

    private fun block(kind: String, text: String) = Block("b1", kind) to text.mapIndexed { i, c ->
        CharNode(i, c.toString(), "b1", 0, i, rect())
    }

    @Test
    fun `竖排加诵读术语判为诵读页`() {
        val (b, chars) = block("body", "般若涅槃南无")
        val v = ContextJudge.judgeBlock(b, chars, true, terms, lexicon)
        assertTrue(v.score >= 6)
        assertEquals(Verdict.SCRIPTURE, v.verdict)
    }

    @Test
    fun `双行夹注额外计分`() {
        val (b1, c1) = block("body", "般若")
        val (b2, c2) = block("interlinear_note", "般若")
        val body = ContextJudge.judgeBlock(b1, c1, true, terms, lexicon).score
        val note = ContextJudge.judgeBlock(b2, c2, true, terms, lexicon).score
        assertTrue(note > body)
    }

    @Test
    fun `现代白话与标点判为普通页`() {
        val (b, chars) = block("body", "这个字可以这样读，你看。")
        assertEquals(Verdict.GENERAL, ContextJudge.judgeBlock(b, chars, true, terms, lexicon).verdict)
    }

    @Test
    fun `版权信息作为强负样本压到普通页`() {
        val (b, chars) = block("body", "版权所有定价般若")
        val v = ContextJudge.judgeBlock(b, chars, true, terms, lexicon)
        assertEquals(Verdict.GENERAL, v.verdict)
        assertTrue(v.evidence.any { it.delta <= -6 })
    }
}

class FormsTest {
    private val lex = Lexicon(
        mapOf(
            "槃" to CharInfo(trad = emptyList(), simp = "盘", vari = listOf("盤", "盘")),
            "读" to CharInfo(trad = listOf("讀"), vari = listOf("讀")),
            "般" to CharInfo(),
        ),
    )

    @Test
    fun `原字恒在首位且字形不重复`() {
        val f = Forms.of("槃", lex)
        assertEquals(listOf("槃", "盘", "盤"), f.map { it.glyph })
        assertEquals(f.size, f.map { it.glyph }.toSet().size)
    }

    @Test
    fun `同形不重复列`() {
        assertEquals(listOf("原字", "繁体"), Forms.of("读", lex).map { it.label })
    }

    @Test
    fun `无候选时只给原字`() {
        assertEquals(1, Forms.of("般", lex).size)
    }
}

class HitTestTest {
    private val chars = listOf(
        CharNode(0, "般", "b", 0, 0, CharRect(0f, 0f, 40f, 24f)),
        CharNode(1, "若", "b", 0, 0, CharRect(40f, 0f, 24f, 24f)),
        CharNode(2, "波", "b", 0, 0, CharRect(0f, 30f, 24f, 24f)),
    )

    @Test
    fun `取最近框中心而非矩形包含`() {
        assertEquals("若", HitTest.nearest(chars, 38f, 12f)?.c)
    }

    @Test
    fun `超出容差不选中`() {
        assertNull(HitTest.nearest(chars, 300f, 300f))
    }
}
