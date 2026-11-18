package com.readtool.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix
import com.readtool.domain.Block
import com.readtool.domain.CharNode
import com.readtool.domain.CharRect
import com.readtool.domain.PageModel
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 端侧 OCR：PP-OCRv4（Apache-2.0）ONNX 模型 + ONNX Runtime。
 * 全流程开源、离线，不依赖任何商业云 API。
 *
 * 管线：检测(DBNet) → 概率图二值化 + 连通域 → 文本行框
 *      → 逐行裁剪（竖排旋转 90°）→ 识别(CTC) → 按字数切分成字级框
 */
class OcrEngine(
    private val assets: AssetManager,
    /** 术语表（ritual + formulas）：用于形近字纠错，见 correctText */
    terms: List<String> = emptyList(),
) {

    private val termSet: Set<String> = terms.toSet()
    // 只让 >=4 字的词参与纠错：3 字词的一字容错太危险
    //（曾把「觀音勢至」误改成「觀大勢至」，因为「音勢至」与术语「大勢至」只差一字）
    private val longTerms: List<String> = terms.filter { it.length >= 4 }.sortedByDescending { it.length }

    private val TAG = "OcrEngine"

    /** 易混字组：纠错只允许在组内替换，避免把不相干的字改掉 */
    private val confusable: Map<Char, Set<Char>> by lazy { loadConfusables() }

    private fun loadConfusables(): Map<Char, Set<Char>> = runCatching {
        val text = assets.open("data/confusables.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONObject(text).optJSONArray("pairs") ?: org.json.JSONArray()
        val m = HashMap<Char, MutableSet<Char>>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONArray(i)
            val chars = (0 until g.length()).mapNotNull { g.optString(it).firstOrNull() }
            for (c in chars) m.getOrPut(c) { mutableSetOf() }.addAll(chars.filter { it != c })
        }
        android.util.Log.i(TAG, "confusables: ${arr.length()} 组 / ${m.size} 字")
        m
    }.getOrElse {
        android.util.Log.w(TAG, "confusables 加载失败: ${it.message}")
        emptyMap()
    }

    private fun isConfusable(a: Char, b: Char): Boolean = confusable[a]?.contains(b) == true

    data class Line(
        val text: String,
        val score: Float,
        val rect: CharRect,
        val vertical: Boolean,
        /** 逐字置信度，与 text 等长；用于标出模型不确定的字 */
        val charScores: List<Float> = emptyList(),
    )

    /** 识别结果：文本 + 平均分 + 逐字分 */
    private data class RecResult(val text: String, val score: Float, val charScores: List<Float>)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var keys: List<String> = emptyList()

    @Volatile
    var ready: Boolean = false
        private set

    /** 最近一次失败原因，便于界面提示 */
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun init(): Boolean {
        if (ready) return true
        return try {
            keys = assets.open("models/keys.txt").bufferedReader().use { it.readText() }
                .split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            require(keys.size > 1000) { "字典行数异常：${keys.size}" }
            val opts = OrtSession.SessionOptions()
            det = env.createSession(assets.open("models/det.onnx").readBytes(), opts)
            rec = env.createSession(assets.open("models/rec.onnx").readBytes(), opts)
            ready = true
            lastError = null
            true
        } catch (t: Throwable) {
            ready = false
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun close() {
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null
        rec = null
        ready = false
    }

    // ---------------- 对外入口 ----------------

    /** 识别整页，返回坐标为「原图像素」的 PageModel；无文字时返回 null */
    fun recognize(bitmap: Bitmap): PageModel? {
        if (!init()) return null
        val lines = detectLines(bitmap)
        if (lines.isEmpty()) return null

        val vertical = lines.count { it.vertical } >= lines.count { !it.vertical }
        val chars = mutableListOf<CharNode>()
        lines.sortedWith(if (vertical) compareBy({ -it.rect.x }, { it.rect.y }) else compareBy({ it.rect.y }, { it.rect.x }))
            .forEachIndexed { lineIdx, ln ->
                val text = ln.text.filter { it != ' ' }
                if (text.isEmpty()) return@forEachIndexed
                val n = text.length
                for (i in 0 until n) {
                    val c = text[i]
                    val box = if (ln.vertical) {
                        val h = ln.rect.h / n
                        CharRect(ln.rect.x, ln.rect.y + h * i, ln.rect.w, h)
                    } else {
                        val w = ln.rect.w / n
                        CharRect(ln.rect.x + w * i, ln.rect.y, w, ln.rect.h)
                    }
                    chars.add(
                        CharNode(
                            index = chars.size,
                            c = c.toString(),
                            blockId = "body",
                            col = lineIdx,
                            row = i,
                            box = box,
                            punct = isPunct(c),
                            conf = ln.charScores.getOrElse(i) { ln.score },
                        )
                    )
                }
            }
        if (chars.count { !it.punct } == 0) return null

        val blocks = listOf(
            Block(
                "body", "body", if (vertical) "v" else "h",
                rect = CharRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            )
        )
        val page = PageModel(vertical, blocks, chars)
        page.buildLines()
        return page
    }

    // ---------------- 检测 ----------------

    private fun detectLines(src: Bitmap): List<Line> {
        val ow = src.width
        val oh = src.height
        if (ow <= 0 || oh <= 0) return emptyList()

        val maxSide = 960f
        val fit = min(maxSide / ow, maxSide / oh)
        val rw = max(32, ((ow * fit).roundToInt() / 32) * 32)
        val rh = max(32, ((oh * fit).roundToInt() / 32) * 32)

        val scaled = Bitmap.createScaledBitmap(src, rw, rh, true)
        val n = rw * rh
        val pixels = IntArray(n)
        scaled.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        if (scaled !== src) scaled.recycle()

        val data = FloatArray(3 * n)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until n) {
            val p = pixels[i]
            data[i] = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            data[n + i] = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            data[2 * n + i] = ((p and 0xFF) / 255f - mean[2]) / std[2]
        }

        val session = det ?: return emptyList()
        val prob = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, 3, rh.toLong(), rw.toLong())
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                detMap(out.get(0).value)
            }
        } ?: return emptyList()

        val boxes = findBoxes(prob, 0.3f, 4)
        val sx = ow.toFloat() / rw
        val sy = oh.toFloat() / rh

        val result = mutableListOf<Line>()
        for (b in boxes) {
            // DB 输出的是「收缩后的文字核」，需要向外扩（等效 unclip），否则字符边缘会被裁掉
            val rawW = (b.x1 - b.x0) * sx
            val rawH = (b.y1 - b.y0) * sy
            val pad = min(rawW, rawH) * 0.15f
            val x0 = (b.x0 * sx - pad).coerceIn(0f, ow.toFloat())
            val y0 = (b.y0 * sy - pad).coerceIn(0f, oh.toFloat())
            val x1 = (b.x1 * sx + pad).coerceIn(0f, ow.toFloat())
            val y1 = (b.y1 * sy + pad).coerceIn(0f, oh.toFloat())
            val w = x1 - x0
            val h = y1 - y0
            if (w < 6f || h < 6f) continue
            val vertical = h > w * 1.25f
            val r = recOne(src, x0, y0, w, h, vertical) ?: continue
            if (r.text.isBlank()) continue
            if (r.score < 0.5f) continue
            val fixed = correctText(r.text)
            // 拼音注音行、纯英文/数字行不含汉字，不参与点字（否则点拼音会弹出"ā 暂无读音"）
            if (!containsHan(fixed)) continue
            result.add(Line(fixed, r.score, CharRect(x0, y0, w, h), vertical, r.charScores))
        }
        return result
    }

    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** DB 后处理：阈值二值化 + 8 邻域连通域，输出外接框 */
    private fun findBoxes(prob: Array<FloatArray>, thresh: Float, minSide: Int): List<Box> {
        val h = prob.size
        if (h == 0) return emptyList()
        val w = prob[0].size
        if (w == 0) return emptyList()

        val visited = BooleanArray(h * w)
        val stack = IntArray(h * w)
        val out = ArrayList<Box>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val start = y * w + x
                if (visited[start] || prob[y][x] < thresh) continue
                var sp = 0
                stack[sp++] = start
                visited[start] = true
                var x0 = x; var x1 = x; var y0 = y; var y1 = y
                var cnt = 0; var sum = 0f
                while (sp > 0) {
                    val p = stack[--sp]
                    val py = p / w
                    val px = p - py * w
                    cnt++
                    sum += prob[py][px]
                    if (px < x0) x0 = px
                    if (px > x1) x1 = px
                    if (py < y0) y0 = py
                    if (py > y1) y1 = py
                    val ny0 = if (py > 0) py - 1 else 0
                    val ny1 = if (py < h - 1) py + 1 else h - 1
                    val nx0 = if (px > 0) px - 1 else 0
                    val nx1 = if (px < w - 1) px + 1 else w - 1
                    var ny = ny0
                    while (ny <= ny1) {
                        var nx = nx0
                        while (nx <= nx1) {
                            val np = ny * w + nx
                            if (!visited[np] && prob[ny][nx] >= thresh) {
                                visited[np] = true
                                stack[sp++] = np
                            }
                            nx++
                        }
                        ny++
                    }
                }
                if (cnt < 16) continue
                if (sum / cnt < 0.5f) continue
                if (x1 - x0 < minSide || y1 - y0 < minSide) continue
                out.add(Box(x0, y0, x1, y1))
            }
        }
        return out
    }

    // ---------------- 识别 ----------------

    /** 裁剪一行并识别，返回 (文本, 平均置信度) */
    private fun recOne(src: Bitmap, x0: Float, y0: Float, w: Float, h: Float, vertical: Boolean): RecResult? {
        val cx = x0.roundToInt().coerceIn(0, src.width - 1)
        val cy = y0.roundToInt().coerceIn(0, src.height - 1)
        val cw = w.roundToInt().coerceAtLeast(1).coerceAtMost(src.width - cx)
        val ch = h.roundToInt().coerceAtLeast(1).coerceAtMost(src.height - cy)
        if (cw < 4 || ch < 4) return null

        var crop = runCatching { Bitmap.createBitmap(src, cx, cy, cw, ch) }.getOrNull() ?: return null
        // 竖排：逆时针转 90°，首字落到左侧，使阅读顺序变为从左到右，符合识别模型的横排分布
        if (vertical) {
            val rotated = runCatching {
                Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true)
            }.getOrNull()
            if (rotated != null) {
                if (rotated !== crop) crop.recycle()
                crop = rotated
            }
        }

        val targetH = 48
        val ratio = crop.width.toFloat() / crop.height.toFloat()
        val targetW = max(16, min(320, (targetH * ratio).roundToInt()))
        val resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true)
        if (resized !== crop) crop.recycle()

        val n = targetW * targetH
        val pixels = IntArray(n)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (resized !== src) resized.recycle()

        // 识别模型归一化：(x/255 - 0.5) / 0.5
        val data = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
            data[n + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
            data[2 * n + i] = (p and 0xFF) / 127.5f - 1f
        }

        val session = rec ?: return null
        val logits = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                recLogits(out.get(0).value)
            }
        } ?: return null

        return ctcDecode(logits)
    }

    /** CTC 贪心解码：argmax → 去 blank(0) → 去连续重复；同时记录每个字的把握度 */
    private fun ctcDecode(logits: Array<FloatArray>): RecResult {
        val sb = StringBuilder()
        val scores = ArrayList<Float>()
        var prev = -1
        var sum = 0f
        var cnt = 0
        for (t in logits.indices) {
            val row = logits[t]
            if (row.isEmpty()) continue
            // 模型输出已归一化（行和≈1），无需再 softmax
            var best = 0
            var bestV = row[0]
            var secondV = 0f
            for (c in 1 until row.size) {
                if (row[c] > bestV) {
                    secondV = bestV
                    bestV = row[c]
                    best = c
                } else if (row[c] > secondV) {
                    secondV = row[c]
                }
            }
            if (best != 0) {
                sum += bestV
                cnt++
                val s = charAt(best)
                // 用 top1-top2 差值作为"把握度"：绝对概率即认识错字也常接近 1，
                // 但「觀/覲」这类形近混淆时 margin 只有 0.1~0.4，是更有判别力的信号
                val margin = (bestV - secondV).coerceIn(0f, 1f)
                if (best != prev) {
                    sb.append(s)
                    scores.add(margin)
                } else if (s.isNotEmpty() && scores.isNotEmpty()) {
                    val last = scores.size - 1
                    if (margin > scores[last]) scores[last] = margin
                }
            }
            prev = best
        }
        return RecResult(sb.toString(), if (cnt > 0) sum / cnt else 0f, scores)
    }

    /** 索引 0 = blank；1..keys.size 对应字典；最后一类为空格 */
    private fun charAt(idx: Int): String = when {
        idx <= 0 -> ""
        idx <= keys.size -> keys[idx - 1]
        else -> " "
    }

    /** 是否含汉字（含扩展 A 与兼容区）；不含汉字的行不进入点字流程 */
    private fun containsHan(s: String): Boolean = s.any {
        val c = it.code
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF
    }

    /**
     * 形近字纠错：模型把「自」认成「目」这类问题，靠字符本身救不回来，
     * 但书页文字有强搭配（觀自在菩薩、般若波羅蜜多）。用术语表做保守替换：
     * 仅当「等长片段只差 1 个字、且该片段本身不是术语、替换后是术语」时才改。
     * 只处理 >=4 字的词并按长词优先——3 字词容易误伤（大勢至 / 音勢至）。
     * 纠错不改变长度，字框坐标不受影响。
     */
    private fun correctText(text: String): String {
        if (longTerms.isEmpty() || text.length < 3) return text
        var s = text
        for (term in longTerms) {
            val len = term.length
            if (len > s.length) continue
            var i = 0
            while (i + len <= s.length) {
                val seg = s.substring(i, i + len)
                if (seg == term) { i += len; continue }
                var diff = 0
                var at = -1
                for (k in 0 until len) {
                    if (seg[k] != term[k]) {
                        diff++
                        at = k
                        if (diff > 1) break
                    }
                }
                // 只差一个字 + 原片段不是术语 + 两者互为易混字 + 目标词是术语 -> 纠正
                if (diff == 1 && !termSet.contains(seg) && isConfusable(seg[at], term[at])) {
                    s = s.replaceRange(i + at, i + at + 1, term[at].toString())
                    i += len
                    continue
                }
                i++
            }
        }
        if (s != text) android.util.Log.i(TAG, "纠正: [$text] -> [$s]")
        return s
    }

    private fun isPunct(c: Char): Boolean =
        "，。！？：；、“”‘’（）《》·—…,.!?:;()".contains(c)

    // ---------------- ONNX 输出提取 ----------------

    /** 检测输出：兼容 [1,1,H,W] 与 [1,H,W] */
    @Suppress("UNCHECKED_CAST")
    private fun detMap(v: Any?): Array<FloatArray>? {
        val a = v as? Array<*> ?: return null
        val b = a.getOrNull(0) as? Array<*> ?: return null
        val c = b.getOrNull(0)
        if (c is FloatArray) return b as Array<FloatArray>
        val d = (c as? Array<*>)?.getOrNull(0)
        if (d is FloatArray) return c as Array<FloatArray>
        return null
    }

    /** 识别输出：兼容 [1,T,C] 与 [T,C] */
    @Suppress("UNCHECKED_CAST")
    private fun recLogits(v: Any?): Array<FloatArray>? {
        val a = v as? Array<*> ?: return null
        val a0 = a.getOrNull(0)
        if (a0 is FloatArray) return a as Array<FloatArray>
        if (a0 is Array<*>) {
            val c = a0.getOrNull(0)
            if (c is FloatArray) return a0 as Array<FloatArray>
        }
        return null
    }
}
