package com.readtool.domain

/** 读音数据：与 app/data/readings.json、lexicon.json 对应 */

data class WordEntry(val w: String, val old: String, val modern: String, val src: String) {
    val diff: Boolean get() = old != modern
}

data class Variant(val p: String, val z: String? = null)

data class CharDiff(val old: Variant, val modern: Variant, val src: String) {
    val diff: Boolean get() = old.p != modern.p
}

data class PolyReading(val p: String, val z: String? = null)

data class CharInfo(
    val p: String? = null,
    val z: String? = null,
    val old: Variant? = null,
    val modern: Variant? = null,
    val poly: List<PolyReading> = emptyList(),
    val polyNote: String? = null,
    val rad: String? = null,
    val strk: Int? = null,
    val struct: String? = null,
    val trad: List<String> = emptyList(),
    val simp: String? = null,
    val vari: List<String> = emptyList(),
    val cp: String? = null,
    val zy: Map<String, String> = emptyMap(),
)

class Lexicon(val chars: Map<String, CharInfo>) {
    operator fun get(c: String): CharInfo? = chars[c]
}

class Readings(
    val words: List<WordEntry>,
    val chars: Map<String, CharDiff>,
    val polyphones: Map<String, List<PolyReading>>,
) {
    val byWord: Map<String, WordEntry> = words.associateBy { it.w }
    val maxLen: Int = words.maxOfOrNull { it.w.length } ?: 2
}

data class ContextTerms(
    val ritual: List<String>,
    val formulas: List<String>,
    val negative: List<String>,
    val modernMarkers: List<String>,
)
