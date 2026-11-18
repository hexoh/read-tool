package com.readtool.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readtool.app.data.Settings
import com.readtool.domain.CharInfo
import com.readtool.domain.Forms
import com.readtool.domain.Lexicon
import com.readtool.domain.MatchType
import com.readtool.domain.PolyReading
import com.readtool.domain.ReadingResult
import kotlin.math.roundToInt

/**
 * 字卡：旧读在上主色、今读在下降灰（位置 + 颜色双重编码），三视图与多音候选都在这里。
 * 结果页浮层与查字页共用。
 */
@Composable
fun CharCard(
    char: String,
    reading: ReadingResult,
    info: CharInfo?,
    lex: Lexicon,
    settings: Settings,
    formKey: String,
    onFormKey: (String) -> Unit,
    onAdd: () -> Unit,
    inVocab: Boolean,
    onCorrect: (() -> Unit)? = null,
    onSpeakPoly: (PolyReading) -> Unit = {},
    /** 该字是否有离线录音；false 时明确告诉用户，避免"点了没声音"的困惑 */
    hasAudio: Boolean = true,
    /** 识别置信度（0..1）；低置信度提示用户核对，避免把错字当对字读 */
    conf: Float = 1f,
) {
    val options = Forms.of(char, lex)
    val current = options.firstOrNull { it.key == formKey } ?: options.first()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = current.glyph,
                fontFamily = FontFamily.Serif,
                fontSize = 52.sp,
                color = Ink,
                modifier = Modifier.width(64.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxWidth()) {
                val primary = reading.readings.firstOrNull()
                if (primary != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(primary.p, color = Primary, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Tag(primary.label.ifBlank { "今读" }, primary.label == "旧读")
                        if (!hasAudio) {
                            Spacer(Modifier.width(6.dp))
                            Text("无录音", color = Warn, fontSize = 10.sp)
                        }
                    }
                    if (settings.zhuyin && primary.z != null) {
                        Text(primary.z ?: "", color = Ink3, fontSize = 12.sp)
                    }
                } else {
                    Text("暂无读音", color = Ink2, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                }
                if (conf < 0.5f) {
                    Text(
                        "模型对此字不确定，请核对原图",
                        color = Warn,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                reading.readings.drop(1).forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Text(c.p, color = Ink2, fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Tag(c.label.ifBlank { "读音" }, false)
                    }
                    if (settings.zhuyin && c.z != null) {
                        Text(c.z ?: "", color = Ink3, fontSize = 12.sp)
                    }
                }
            }
        }

        if (options.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { f ->
                    val on = f.key == current.key
                    Surface(
                        color = if (on) PrimaryDim else Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (on) Primary else LineC),
                        modifier = Modifier.clickable { onFormKey(f.key) },
                    ) {
                        Text(
                            "${f.label} ${f.glyph}",
                            fontSize = 11.sp,
                            color = if (on) Primary else Ink2,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                }
                if (options.any { it.more.isNotEmpty() }) {
                    Text("多形候选，需按语境判断", fontSize = 10.sp, color = Warn)
                }
            }
        }

        val meta = listOfNotNull(
            info?.rad?.let { "部首 $it" },
            info?.strk?.let { "$it 画" },
            info?.struct,
            info?.cp,
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, fontSize = 11.sp, color = Ink3, modifier = Modifier.padding(top = 8.dp))
        }

        val src = listOfNotNull(
            reading.phrase?.let { "词条「$it」" },
            reading.source.ifBlank { null },
            if (reading.matchType == MatchType.OVERRIDE) "已按你的纠正" else null,
            if (reading.approx) "字库未收，按通用读音" else null,
        ).joinToString(" · ")
        if (src.isNotEmpty()) {
            Text(src, fontSize = 11.sp, color = Ink2, modifier = Modifier.padding(top = 3.dp))
        }

        val polys = reading.poly.filter { it.p != reading.p }
        if (polys.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("多音：", fontSize = 11.sp, color = Ink3)
                polys.forEach { p ->
                    Surface(
                        color = Color(0xFFF1EEE7),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, LineC),
                        modifier = Modifier.clickable { onSpeakPoly(p) },
                    ) {
                        Text(
                            p.p + (p.z?.let { " $it" } ?: ""),
                            fontSize = 11.sp, color = Ink2,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Text(if (inVocab) "已在生字本" else "加入生字本", fontSize = 13.sp)
            }
            if (onCorrect != null) {
                OutlinedButton(onClick = onCorrect, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Text("纠正读音", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun Tag(text: String, primary: Boolean) {
    Surface(color = if (primary) PrimaryDim else Color(0xFFF1EEE7), shape = RoundedCornerShape(4.dp)) {
        Text(
            text,
            fontSize = 10.sp,
            color = if (primary) Primary else Ink3,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Int = 56) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
    ) {
        Text(text, fontSize = 17.sp, color = Color.White)
    }
}
