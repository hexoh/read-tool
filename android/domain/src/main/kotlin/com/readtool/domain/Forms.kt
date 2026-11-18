package com.readtool.domain

/**
 * 字形三视图：原字 / 繁体 / 简体 / 异体。
 * 红线 B-03 不改原文 —— 三视图只改展示字形；一简多繁只给候选并标注，不做猜测。
 */
data class FormOption(
    val key: String,
    val label: String,
    val glyph: String,
    val more: List<String> = emptyList(),
)

object Forms {
    fun of(c: String, lex: Lexicon?): List<FormOption> {
        val info = lex?.get(c)
        val used = linkedSetOf(c)
        val out = mutableListOf(FormOption("origin", "原字", c))

        val trad = (info?.trad ?: emptyList()).filter { used.add(it) }
        if (trad.isNotEmpty()) out.add(FormOption("trad", "繁体", trad[0], trad.drop(1)))

        val simp = info?.simp
        if (simp != null && used.add(simp)) out.add(FormOption("simp", "简体", simp))

        val vari = (info?.vari ?: emptyList()).filter { used.add(it) }
        if (vari.isNotEmpty()) out.add(FormOption("vari", "异体", vari[0], vari.drop(1)))

        return out
    }
}
