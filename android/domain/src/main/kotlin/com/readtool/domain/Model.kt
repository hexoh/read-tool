package com.readtool.domain

/** 页面模型：与网页端 app/js/render/page.js 的输出一一对应 */
data class CharRect(val x: Float, val y: Float, val w: Float, val h: Float)

data class CharNode(
    val index: Int,
    val c: String,
    val blockId: String,
    val col: Int,
    val row: Int,
    val box: CharRect,
    val punct: Boolean = false,
    val note: Boolean = false,
    /** 识别置信度（0..1）。非 OCR 来源（样张渲染、手动录入）固定为 1 */
    val conf: Float = 1f,
)

data class Block(
    val id: String,
    val kind: String,
    val axis: String = "v",
    val skipWhenReading: Boolean = false,
    val rect: CharRect? = null,
)

data class Line(val blockId: String, val vertical: Boolean, val chars: List<CharNode>) {
    val text: String = chars.joinToString("") { it.c }
}

data class LineHit(val line: Line, val idx: Int)

enum class Verdict { SCRIPTURE, UNCERTAIN, GENERAL }

class PageModel(
    val vertical: Boolean,
    val blocks: List<Block>,
    val chars: List<CharNode>,
) {
    var lines: List<Line> = emptyList()
        private set

    var lineOf: Map<Int, LineHit> = emptyMap()
        private set

    var verdict: Verdict = Verdict.GENERAL
    var score: Int = 0
    var judgement: List<BlockVerdict> = emptyList()

    /** 页级手动切换：null 表示按判定结果 */
    var modeOverride: String? = null

    /**
     * 阅读顺序：竖排列号升序（列 0 为最右列）= 右起向左；横排行号升序 = 自上而下。
     * 页眉等块可用 axis="h" 单独指定。
     */
    fun buildLines() {
        val axis = blocks.associate { it.id to it.axis }
        val grouped = LinkedHashMap<String, MutableMap<Int, MutableList<CharNode>>>()
        for (ch in chars) {
            val m = grouped.getOrPut(ch.blockId) { LinkedHashMap() }
            val v = (axis[ch.blockId] ?: if (vertical) "v" else "h") == "v"
            val key = if (v) ch.col else ch.row
            m.getOrPut(key) { mutableListOf() }.add(ch)
        }
        val out = mutableListOf<Line>()
        val index = HashMap<Int, LineHit>()
        for ((blockId, m) in grouped) {
            val v = (axis[blockId] ?: if (vertical) "v" else "h") == "v"
            for (k in m.keys.sorted()) {
                val arr = m.getValue(k).sortedBy { if (v) it.row else it.col }
                val line = Line(blockId, v, arr)
                out.add(line)
                arr.forEachIndexed { i, ch -> index[ch.index] = LineHit(line, i) }
            }
        }
        lines = out
        lineOf = index
    }

    fun modeFor(readingDefault: String): String {
        modeOverride?.let { return it }
        if (verdict == Verdict.SCRIPTURE) return "scripture"
        return if (readingDefault == "scripture") "scripture" else "general"
    }

    /** 朗读顺序：默认跳过注文与标点 */
    fun readingOrder(skipNotes: Boolean = true): List<CharNode> {
        val skip = blocks.filter { it.skipWhenReading }.map { it.id }.toSet()
        return lines
            .filter { !(skipNotes && skip.contains(it.blockId)) }
            .flatMap { it.chars }
            .filter { !it.punct }
    }
}
