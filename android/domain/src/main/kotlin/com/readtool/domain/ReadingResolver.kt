package com.readtool.domain

/**
 * 读音解析：用户覆盖 → 词条语境 → 单字差异集 → 现代读音（→ 通用读音兜底）
 * 与网页端 app/js/engine/reading.js 同构，用例见 domain/src/test。
 *
 * 红线：B-01 不猜读音（没有依据就回落现代读音并标记）；B-02 不隐藏读音（双音同时返回）。
 */
enum class MatchType { OVERRIDE, PHRASE, CHAR, PLAIN, UNKNOWN }

data class Candidate(val variant: String, val p: String, val z: String?, val label: String)

data class PhraseHit(val word: String, val entry: WordEntry, val offset: Int)

data class ReadingResult(
    val char: String,
    val variant: String?,
    val p: String?,
    val z: String?,
    val label: String,
    val matchType: MatchType,
    val phrase: String?,
    val source: String,
    val note: String,
    val readings: List<Candidate>,
    val poly: List<PolyReading>,
    val approx: Boolean,
    val audioIds: List<String>,
)

class ReadingResolver(
    private val readings: Readings,
    private val lexicon: Lexicon,
    private val fallback: Map<String, String> = emptyMap(),
) {
    fun matchPhrase(page: PageModel, ch: CharNode): PhraseHit? {
        val hit = page.lineOf[ch.index] ?: return null
        val text = hit.line.text
        val longest = minOf(readings.maxLen, text.length)
        for (len in longest downTo 2) {
            var s = maxOf(0, hit.idx - len + 1)
            while (s <= hit.idx && s + len <= text.length) {
                val sub = text.substring(s, s + len)
                val entry = readings.byWord[sub]
                if (entry != null) return PhraseHit(sub, entry, hit.idx - s)
                s++
            }
        }
        return null
    }

    fun resolve(
        page: PageModel,
        ch: CharNode,
        mode: String,
        overrides: Map<String, String> = emptyMap(),
    ): ReadingResult {
        val lex = lexicon[ch.c]
        val diff = readings.chars[ch.c]
        val phrase = matchPhrase(page, ch)
        val entry = phrase?.entry

        fun syllableAt(full: String?): String {
            val arr = (full ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return arr.getOrNull(phrase?.offset ?: 0) ?: arr.firstOrNull() ?: ""
        }
        fun zy(p: String?): String? = if (p.isNullOrBlank()) null else lex?.zy?.get(p)

        val list = mutableListOf<Candidate>()
        fun push(variant: String, p: String?, z: String?, label: String) {
            if (p.isNullOrBlank()) return
            if (list.any { it.p == p }) return
            list.add(Candidate(variant, p, z, label))
        }

        if (entry != null && entry.diff) {
            push("old", entry.old, zy(syllableAt(entry.old)), "旧读")
            push("modern", entry.modern, zy(syllableAt(entry.modern)) ?: lex?.z, "今读")
        } else if (diff != null && diff.diff) {
            push("old", diff.old.p, diff.old.z, "旧读")
            push("modern", diff.modern.p, diff.modern.z, "今读")
        }

        var matchType = MatchType.PLAIN
        var source = ""
        var primary: String? = when {
            entry != null -> { matchType = MatchType.PHRASE; source = entry.src; if (entry.diff) "old" else "modern" }
            diff != null && diff.diff -> { matchType = MatchType.CHAR; source = diff.src; if (mode == "scripture") "old" else "modern" }
            lex?.p != null -> { matchType = MatchType.PLAIN; "modern" }
            fallback.containsKey(ch.c) -> { matchType = MatchType.PLAIN; "modern" }
            else -> { matchType = MatchType.UNKNOWN; null }
        }
        overrides[ch.c]?.let { primary = it; matchType = MatchType.OVERRIDE }

        var p: String? = null
        var z: String? = null
        var label = ""
        when {
            entry != null -> {
                p = if (entry.diff && primary == "old") entry.old else entry.modern
                z = zy(syllableAt(p)) ?: lex?.z
                label = if (entry.diff) (if (primary == "old") "旧读" else "今读") else ""
            }
            diff != null && diff.diff -> {
                p = if (primary == "old") diff.old.p else diff.modern.p
                z = if (primary == "old") diff.old.z else diff.modern.z
                label = if (primary == "old") "旧读" else "今读"
            }
            lex?.p != null -> { p = lex.p; z = lex.z }
            else -> p = fallback[ch.c]
        }

        val idx = list.indexOfFirst { it.p == p }
        if (idx > 0) list.add(0, list.removeAt(idx))
        if (idx == -1 && p != null) list.add(0, Candidate(primary ?: "modern", p, z, label))
        if (list.isNotEmpty() && list[0].z == null && z != null) list[0] = list[0].copy(z = z)

        val audio = mutableListOf<String>()
        phrase?.let {
            audio.add("w:${it.word}:$primary")
            audio.add("w:${it.word}:any")
        }
        audio.add("c:${ch.c}:$primary")
        audio.add("c:${ch.c}:any")

        return ReadingResult(
            char = ch.c,
            variant = primary,
            p = p,
            z = z,
            label = label,
            matchType = matchType,
            phrase = phrase?.word,
            source = source,
            note = lex?.polyNote ?: "",
            readings = list,
            poly = lex?.poly ?: emptyList(),
            approx = p != null && lex?.p == null,
            audioIds = audio,
        )
    }
}
