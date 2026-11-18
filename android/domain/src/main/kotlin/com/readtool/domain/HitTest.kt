package com.readtool.domain

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 命中测试：取点击点最近的框中心，不放大热区
 * （放大热区会让相邻字框重叠而点错字，见 ui-design §3.1 规则 4）。
 */
object HitTest {
    fun nearest(
        chars: List<CharNode>,
        x: Float,
        y: Float,
        maxDist: Float = 32f,
        patience: Float = 0.95f,
    ): CharNode? {
        var best: CharNode? = null
        var bestD = Float.MAX_VALUE
        for (ch in chars) {
            if (ch.punct) continue
            val cx = ch.box.x + ch.box.w / 2f
            val cy = ch.box.y + ch.box.h / 2f
            val d = hypot(cx - x, cy - y)
            val limit = min(maxDist * 2f, max(ch.box.w, ch.box.h) * patience)
            if (d <= limit && d < bestD) {
                bestD = d
                best = ch
            }
        }
        return best
    }
}
