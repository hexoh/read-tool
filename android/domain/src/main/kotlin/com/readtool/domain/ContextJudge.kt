package com.readtool.domain

/**
 * 块级语境判定（ADR-004）：加权证据 + 保守阈值，纯规则、可解释、毫秒级。
 * 误判为诵读页的代价 > 漏判，所以没到 6 分一律按普通页走今读。
 */
data class Evidence(val label: String, val delta: Int, val detail: String)

data class BlockVerdict(
    val blockId: String,
    val score: Int,
    val verdict: Verdict,
    val evidence: List<Evidence>,
)

object ContextJudge {
    const val SCRIPTURE = 6
    const val UNCERTAIN = 2

    private val MODERN_PUNCT = listOf(
        "，", "。", "！", "？", "：", "；", "“", "”", "（", "）", "《", "》",
    )

    fun judgeBlock(
        block: Block,
        chars: List<CharNode>,
        vertical: Boolean,
        terms: ContextTerms,
        lexicon: Lexicon,
    ): BlockVerdict {
        val text = chars.joinToString("") { it.c }
        val ev = mutableListOf<Evidence>()
        fun add(label: String, delta: Int, detail: String) {
            if (delta != 0) ev.add(Evidence(label, delta, detail))
        }

        if (vertical) add("版式·竖排", 2, "竖排右起是古籍常见版式")
        if (block.kind == "interlinear_note") add("结构·双行夹注", 3, "双行小字夹注")

        val hits = terms.ritual.filter { it.length >= 2 && text.contains(it) }
        if (hits.isNotEmpty()) add("词汇·诵读术语", minOf(6, hits.size * 2), hits.take(5).joinToString("、"))

        val formulas = terms.formulas.filter { text.contains(it) }
        if (formulas.isNotEmpty()) add("词汇·套语", 4, formulas.take(3).joinToString("、"))

        val cjk = chars.filter { it.c.isNotEmpty() && it.c[0] in '\u3400'..'\u9FFF' }
        if (cjk.isNotEmpty()) {
            val simpLike = cjk.count { ch ->
                val t = lexicon[ch.c]?.trad
                t != null && t.isNotEmpty() && t[0] != ch.c
            }
            val ratioSimp = simpLike.toFloat() / cjk.size
            if (ratioSimp < 0.08f) add("文字·繁体为主", 2, "简体特征字仅 ${(ratioSimp * 100).toInt()}%")
            else if (ratioSimp > 0.55f) add("文字·简体为主", -1, "简体特征字 ${(ratioSimp * 100).toInt()}%")

            val rare = cjk.count { (lexicon[it.c]?.strk ?: 0) >= 16 || lexicon[it.c] == null }
            if (rare.toFloat() / cjk.size > 0.12f) add("文字·生僻字多", 1, "笔画≥16 占 ${(rare * 100 / cjk.size)}%")
        }

        val neg = terms.negative.filter { text.contains(it) }
        if (neg.isNotEmpty()) add("负样本·现代印刷信息", -6, neg.take(3).joinToString("、"))

        val modern = terms.modernMarkers.filter { text.contains(it) }
        if (modern.isNotEmpty()) add("负样本·现代白话", -minOf(3, modern.size), modern.take(3).joinToString("、"))

        val punct = MODERN_PUNCT.sumOf { p -> text.windowed(p.length).count { it == p } }
        if (punct > 0) add("负样本·现代标点", -minOf(3, punct), "$punct 处")

        val score = ev.sumOf { it.delta }
        val verdict = when {
            score >= SCRIPTURE -> Verdict.SCRIPTURE
            score >= UNCERTAIN -> Verdict.UNCERTAIN
            else -> Verdict.GENERAL
        }
        return BlockVerdict(block.id, score, verdict, ev.sortedByDescending { kotlin.math.abs(it.delta) })
    }

    fun judgePage(page: PageModel, terms: ContextTerms, lexicon: Lexicon) {
        val results = page.blocks.mapNotNull { b ->
            val chars = page.chars.filter { it.blockId == b.id }
            if (chars.isEmpty()) null else judgeBlock(b, chars, page.vertical, terms, lexicon)
        }
        val best = results.maxByOrNull { it.score }
        page.judgement = results
        page.verdict = best?.verdict ?: Verdict.GENERAL
        page.score = best?.score ?: 0
    }
}
