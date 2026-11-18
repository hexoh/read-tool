package com.readtool.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.readtool.app.data.PageSpec
import com.readtool.domain.Block
import com.readtool.domain.CharNode
import com.readtool.domain.CharRect
import com.readtool.domain.PageModel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

data class RenderedPage(val bitmap: Bitmap, val page: PageModel)

/**
 * 排版渲染：把「文字」排成一页可点击的样张（与网页端 app/js/render/page.js 同构）。
 * 正式版这里应由 OCR 结果替代 —— 字框来自识别，图片来自相机；本渲染器用于示例页与手动录入页。
 */
object PageRenderer {
    private const val PAPER = 0xFFF6F1E4.toInt()
    private const val INK = 0xFF2A2724.toInt()
    private const val RULE = 0x38786E5A

    fun render(spec: PageSpec): RenderedPage =
        if (spec.vertical) vertical(spec) else horizontal(spec)

    /**
     * 把照片解码为底图 Bitmap（大图先按 inSampleSize 降采样，避免 OOM）。
     * 返回 null 表示文件已失效（如 cache 被系统清理），调用方回退到样张渲染。
     */
    fun loadPhoto(path: String, maxDim: Int = 2400): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds=true 时 decodeFile 正常返回 null，仅填充尺寸，不能据此判失败
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    private fun isPunct(c: Char): Boolean =
        "，。！？：；、“”‘’（）《》·—…,.!?:;()".contains(c)

    private fun isCjk(c: Char): Boolean =
        c in '\u3400'..'\u9FFF' || c in '\uF900'..'\uFAFF'

    private fun vertical(spec: PageSpec): RenderedPage {
        val fs = spec.fontSize
        val cols = spec.cols.coerceAtLeast(1)
        val rows = spec.charsPerCol.coerceAtLeast(1)
        val pad = (fs * 0.9f).roundToInt()
        val colW = (fs * 1.5f).roundToInt()
        val headerFs = (fs * 0.34f).roundToInt().coerceAtLeast(12)
        val noteFs = (fs * 0.42f).roundToInt().coerceAtLeast(12)
        val noteLines = 2
        val noteChars = spec.note.filter { it != ' ' }
        val noteRows = if (noteChars.isEmpty()) 0 else ceil(noteChars.length / 2.0).toInt()
        val noteStep = (noteFs * 1.4f).roundToInt()
        val noteW = if (noteChars.isEmpty()) 0 else noteLines * noteStep + (noteFs * 0.8f).roundToInt()
        val height = pad * 2 + headerFs * 2 + rows * fs
        val width = pad * 2 + max(cols * colW + noteW, noteLines * (noteFs * 1.5f).roundToInt())

        val blocks = mutableListOf<Block>()
        val chars = mutableListOf<CharNode>()

        if (spec.title.isNotEmpty()) {
            blocks.add(Block("header", "header_footer", "h", rect = CharRect(0f, pad * 0.4f, width.toFloat(), headerFs * 1.4f)))
            val titleChars = spec.title.filter { !isPunct(it) && it != ' ' }
            val step = headerFs * 1.15f
            var x = (width - titleChars.length * step) / 2f
            titleChars.forEachIndexed { i, c ->
                chars.add(CharNode(chars.size, c.toString(), "header", i, 0, CharRect(x, pad * 0.4f, step, headerFs.toFloat())))
                x += step
            }
        }

        blocks.add(Block("body", "body", "v", rect = CharRect(pad.toFloat(), (pad + headerFs * 2).toFloat(), (cols * colW).toFloat(), (rows * fs).toFloat())))

        var col = 0
        var row = 0
        for (c in spec.text) {
            if (c == ' ' || c == '\n' || isPunct(c)) continue
            if (row >= rows) { row = 0; col += 1 }
            if (col >= cols) break
            val x = width - pad - (col + 1) * colW + ((colW - fs) / 2f)
            val y = pad + headerFs * 2 + row * fs
            chars.add(CharNode(chars.size, c.toString(), "body", col, row, CharRect(x, y.toFloat(), fs.toFloat(), fs.toFloat())))
            row += 1
        }
        val usedCols = max(1, col + if (row > 0) 1 else 0)

        if (noteChars.isNotEmpty()) {
            val noteX = width - pad - usedCols * colW - noteW + (noteFs * 0.4f).roundToInt()
            blocks.add(
                Block(
                    "note", "interlinear_note", "v", skipWhenReading = true,
                    rect = CharRect(noteX.toFloat(), (pad + headerFs * 2).toFloat(), (noteLines * noteStep).toFloat(), (noteRows * noteFs).toFloat()),
                )
            )
            val half = ceil(noteChars.length / 2.0).toInt()
            val parts = listOf(noteChars.take(half), noteChars.drop(half))
            parts.forEachIndexed { ci, part ->
                part.forEachIndexed { ri, c ->
                    if (isPunct(c)) return@forEachIndexed
                    val x = noteX + (noteLines - 1 - ci) * noteStep + (noteFs * 0.2f).roundToInt()
                    val y = pad + headerFs * 2 + ri * noteFs
                    chars.add(
                        CharNode(
                            chars.size, c.toString(), "note", ci, ri,
                            CharRect(x.toFloat(), y.toFloat(), noteFs.toFloat(), noteFs.toFloat()),
                            note = true,
                        )
                    )
                }
            }
        }

        val bitmap = paint(width, height, chars)
        val page = PageModel(true, blocks, chars)
        page.buildLines()
        return RenderedPage(bitmap, page)
    }

    private fun horizontal(spec: PageSpec): RenderedPage {
        val fs = spec.fontSize
        val lineChars = spec.lineChars.coerceAtLeast(1)
        val pad = (fs * 1.2f).roundToInt()
        val lineH = (fs * 1.75f).roundToInt()
        val headerFs = (fs * 0.62f).roundToInt().coerceAtLeast(14)
        val text = spec.text.filter { it != '\n' }
        val rows = max(1, ceil(text.length.toDouble() / lineChars).toInt())
        val width = pad * 2 + (lineChars * fs * 1.06f).roundToInt()
        val headerH = if (spec.title.isNotEmpty()) headerFs * 2.2f else 0f
        val height = pad * 2 + headerH.roundToInt() + rows * lineH

        val blocks = mutableListOf<Block>()
        val chars = mutableListOf<CharNode>()

        if (spec.title.isNotEmpty()) {
            blocks.add(Block("header", "header_footer", "h", rect = CharRect(0f, pad * 0.6f, width.toFloat(), headerFs.toFloat())))
            var x = pad.toFloat()
            spec.title.forEachIndexed { i, c ->
                if (isPunct(c) || c == ' ') return@forEachIndexed
                chars.add(CharNode(chars.size, c.toString(), "header", i, 0, CharRect(x, pad * 0.6f, headerFs.toFloat(), headerFs.toFloat())))
                x += headerFs * 1.1f
            }
        }

        blocks.add(Block("body", "body", "h", rect = CharRect(pad.toFloat(), pad.toFloat(), (width - pad * 2).toFloat(), (rows * lineH).toFloat())))

        text.forEachIndexed { k, c ->
            val row = k / lineChars
            val col = k % lineChars
            val x = pad + col * fs * 1.06f
            val y = pad + headerH + row * lineH
            chars.add(
                CharNode(
                    chars.size, c.toString(), "body", col, row,
                    CharRect(x, y, fs.toFloat(), fs.toFloat()),
                    punct = isPunct(c),
                )
            )
        }

        val bitmap = paint(width, height, chars)
        val page = PageModel(false, blocks, chars)
        page.buildLines()
        return RenderedPage(bitmap, page)
    }

    private fun paint(width: Int, height: Int, chars: List<CharNode>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val g = Canvas(bitmap)
        g.drawColor(PAPER)

        // 纸纹：低对比噪点，保证原图有纸感（不做滤镜，暗色模式下也不反转）
        val rnd = Random(42)
        val noise = Paint()
        repeat(900) {
            noise.color = Color.argb((3 + rnd.nextInt(5)), 120, 104, 72)
            val x = rnd.nextFloat() * width
            val y = rnd.nextFloat() * height
            g.drawRect(x, y, x + 1 + rnd.nextFloat() * 2, y + 1 + rnd.nextFloat() * 2, noise)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            color = INK
        }
        for (ch in chars) {
            paint.textSize = min(ch.box.w, ch.box.h) * 0.96f
            val fm = paint.fontMetrics
            val cx = ch.box.x + ch.box.w / 2f
            val cy = ch.box.y + ch.box.h / 2f - (fm.ascent + fm.descent) / 2f
            g.drawText(ch.c, cx, cy, paint)
        }
        return bitmap
    }
}
