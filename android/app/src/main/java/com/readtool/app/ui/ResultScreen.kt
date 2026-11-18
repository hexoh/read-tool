package com.readtool.app.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readtool.app.PageUi
import com.readtool.app.audio.SoundPlayer
import com.readtool.app.data.AppData
import com.readtool.app.data.Store
import com.readtool.app.data.VocabItem
import com.readtool.domain.CharNode
import com.readtool.domain.HitTest
import com.readtool.domain.MatchType
import com.readtool.domain.ReadingResolver
import com.readtool.domain.Verdict
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

private val FRAME = Color(0x73786E5A)
private val FRAME_NOTE = Color(0x57786E5A)

/** 字框层相对底图的校准：把虚拟纸面坐标映射到照片像素坐标 */
private data class FrameCalibration(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/** 初始校准：OCR 结果已对齐照片或没有照片时为单位变换；手动录入页把虚拟纸面 cover 到照片 */
private fun initialFrame(ui: com.readtool.app.PageUi): FrameCalibration {
    if (ui.aligned) return FrameCalibration()
    val photo = ui.photo ?: return FrameCalibration()
    val pw = ui.bitmap.width.toFloat()
    val ph = ui.bitmap.height.toFloat()
    val s = min(photo.width.toFloat() / pw, photo.height.toFloat() / ph)
    return FrameCalibration(
        scale = s,
        offset = Offset((photo.width - pw * s) / 2f, (photo.height - ph * s) / 2f),
    )
}

@Composable
fun ResultScreen(
    ui: PageUi,
    data: AppData,
    store: Store,
    player: SoundPlayer,
    onBack: () -> Unit,
) {
    val page = ui.page
    val resolver = remember(data) { ReadingResolver(data.readings, data.lexicon, data.fallback) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var modeOverride by remember { mutableStateOf(page.modeOverride) }
    val mode = modeOverride ?: page.modeFor(store.settings.defaultMode)
    val settings = store.settings

    var selected by remember { mutableStateOf<CharNode?>(null) }
    var formKey by remember { mutableStateOf("origin") }
    var correctionFor by remember { mutableStateOf<CharNode?>(null) }
    var textView by remember { mutableStateOf(false) }
    var readIndex by remember { mutableStateOf(-1) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    var calibrating by remember { mutableStateOf(false) }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fitted by remember { mutableStateOf(false) }

    val hasPhoto = ui.photoImage != null
    // 底图尺寸：有照片用照片，否则用样张渲染图
    val baseW: Float
    val baseH: Float
    if (hasPhoto) {
        baseW = ui.photo!!.width.toFloat()
        baseH = ui.photo.height.toFloat()
    } else {
        baseW = ui.bitmap.width.toFloat()
        baseH = ui.bitmap.height.toFloat()
    }
    val pageW = baseW
    val pageH = baseH

    // 字框校准：默认把虚拟纸面等比铺到照片中央，用户可拖动/缩放微调
    var frame by remember(ui) { mutableStateOf(initialFrame(ui)) }

    fun fitScale(): Float {
        if (viewport.width == 0 || viewport.height == 0) return 1f
        return min(viewport.width * 0.99f / pageW, viewport.height * 0.99f / pageH)
    }

    fun scale(): Float = fitScale() * zoom

    fun centerOffset(): Offset {
        val s = scale()
        return Offset((viewport.width - pageW * s) / 2f, (viewport.height - pageH * s) / 2f)
    }

    LaunchedEffect(viewport, ui) {
        frame = initialFrame(ui)
        if (!fitted && viewport.width > 0) {
            offset = centerOffset()
            fitted = true
        }
    }

    /** 屏幕坐标 → 底图像素坐标 */
    fun toBase(pos: Offset): Offset {
        val s = scale()
        return Offset((pos.x - offset.x) / s, (pos.y - offset.y) / s)
    }

    /** 底图像素坐标 → 虚拟纸面坐标（字框坐标系） */
    fun toPaper(base: Offset): Offset = Offset(
        (base.x - frame.offset.x) / frame.scale,
        (base.y - frame.offset.y) / frame.scale,
    )

    fun stopRead() {
        readJob?.cancel()
        readJob = null
        readIndex = -1
        player.stop()
    }

    fun startRead(order: List<CharNode>, from: Int = 0) {
        stopRead()
        val rate = settings.readRate.coerceIn(0.5f, 1.5f)
        readJob = scope.launch {
            var missing = 0
            for (i in from until order.size) {
                val ch = order[i]
                readIndex = ch.index
                val r = resolver.resolve(page, ch, mode, store.overrides())
                val ms = player.play(r.audioIds, rate)
                if (ms <= 0) {
                    missing++
                    player.speak(r.p ?: ch.c, rate)
                    delay(300)
                } else {
                    delay(ms.toLong() + if (mode == "scripture") 150 else 60)
                }
            }
            readIndex = -1
            readJob = null
            // 有字没有录音时明确告知，避免用户以为播放坏了
            if (missing > 0) {
                Toast.makeText(
                    context,
                    "本页 $missing 个字暂无录音，已跳过",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun speakOne(ch: CharNode) {
        val r = resolver.resolve(page, ch, mode, store.overrides())
        scope.launch {
            val ms = player.play(r.audioIds, if (mode == "scripture") settings.readRate else 1f)
            if (ms <= 0) player.speak(r.p ?: ch.c)
        }
    }

    fun onTap(pos: Offset) {
        val s = scale()
        if (s <= 0f) return
        if (calibrating) {
            // 校准模式：点按只选中字框、不发音，方便对照调整
            val paper = toPaper(toBase(pos))
            val ch = HitTest.nearest(page.chars, paper.x, paper.y, maxDist = 32f / (s * frame.scale)) ?: return
            if (selected?.index != ch.index) formKey = "origin"
            selected = ch
            return
        }
        val paper = toPaper(toBase(pos))
        val ch = HitTest.nearest(page.chars, paper.x, paper.y, maxDist = 32f / (s * frame.scale)) ?: return
        if (selected?.index != ch.index) formKey = "origin"
        selected = ch
        speakOne(ch)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(
            title = ui.spec.title,
            onBack = { stopRead(); onBack() },
            action = {
                Text("⤢", fontSize = 18.sp, color = Ink2, modifier = Modifier.clickable {
                    zoom = 1f
                    offset = centerOffset()
                    frame = initialFrame(ui)
                })
            },
        )

        // 判定状态条：普通页不显示，保持安静
        val bannerText = when {
            page.verdict == Verdict.SCRIPTURE -> "本页判定为诵读页，多音字按旧读。"
            page.verdict == Verdict.UNCERTAIN -> "疑似诵读页，默认按${if (mode == "scripture") "旧读" else "今读"}（宁可漏判）。"
            modeOverride != null -> "已手动改用${if (mode == "scripture") "旧读" else "今读"}。"
            else -> null
        }
        if (bannerText != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(if (page.verdict == Verdict.SCRIPTURE) PrimaryDim else Color(0xFFFBF3E2), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(bannerText, fontSize = 12.sp, color = Color(0xFF14508C), modifier = Modifier.weight(1f))
                Text(
                    if (mode == "scripture") "改用今读" else "改用旧读",
                    fontSize = 12.sp, color = Color(0xFF14508C),
                    modifier = Modifier.clickable {
                        modeOverride = if (mode == "scripture") "general" else "scripture"
                    },
                )
            }
        }

        // 校准模式提示条：仅照片页进入校准时出现
        if (hasPhoto && calibrating) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(PrimaryDim, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("拖动/双指缩放字框，对齐照片上的字", fontSize = 12.sp, color = Color(0xFF14508C), modifier = Modifier.weight(1f))
                Text("重置", fontSize = 12.sp, color = Color(0xFF14508C), modifier = Modifier.clickable {
                    frame = initialFrame(ui)
                })
            }
        }

        // 原文区 / 文本视图
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
                .background(Paper, RoundedCornerShape(8.dp))
                .onSizeChanged { viewport = it },
        ) {
            if (textView) {
                TextView(page, resolver, mode, store) { ch ->
                    selected = ch
                    formKey = "origin"
                    speakOne(ch)
                }
            } else {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(calibrating) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                val oldS = scale()
                                if (oldS <= 0f) return@detectTransformGestures
                                if (calibrating && hasPhoto) {
                                    // 校准模式：手势作用于字框层（base 坐标系）
                                    val centroidBase = toBase(centroid)
                                    val paperAtCentroid = toPaper(centroidBase)
                                    val newFrameScale = (frame.scale * gestureZoom).coerceIn(0.2f, 6f)
                                    frame = FrameCalibration(
                                        scale = newFrameScale,
                                        offset = Offset(
                                            centroidBase.x + pan.x / oldS - paperAtCentroid.x * newFrameScale,
                                            centroidBase.y + pan.y / oldS - paperAtCentroid.y * newFrameScale,
                                        ),
                                    )
                                } else {
                                    val newZoom = (zoom * gestureZoom).coerceIn(0.35f, 4f)
                                    val imgPoint = (centroid - offset) / oldS
                                    zoom = newZoom
                                    val newS = fitScale() * newZoom
                                    offset = centroid - imgPoint * newS + pan
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { onTap(it) }
                        }
                ) {
                    val s = fitScale() * zoom
                    withTransform({
                        translate(offset.x, offset.y)
                        scale(s, s, pivot = Offset.Zero)
                    }) {
                        // 底图：照片优先，否则样张渲染图
                        if (hasPhoto) drawImage(ui.photoImage!!, topLeft = Offset.Zero)
                        else drawImage(ui.image, topLeft = Offset.Zero)

                        // 字框层：套用校准变换，把虚拟纸面坐标映射到 base 坐标
                        withTransform({
                            translate(frame.offset.x, frame.offset.y)
                            scale(frame.scale, frame.scale, pivot = Offset.Zero)
                        }) {
                            if (settings.hideNotes) {
                                page.blocks.filter { it.kind == "interlinear_note" }.forEach { b ->
                                    val r = b.rect ?: return@forEach
                                    drawRect(Paper, Offset(r.x - 6f, r.y - 6f), Size(r.w + 12f, r.h + 12f))
                                }
                            }

                            val thin = 1f / (s * frame.scale)
                            for (ch in page.chars) {
                                if (ch.punct) continue
                                if (ch.note && settings.hideNotes) continue
                                val isSel = selected?.index == ch.index
                                val isReading = readIndex == ch.index
                                val alpha = if (selected != null && !isSel) 0.3f else 1f
                                if (isSel) {
                                    drawRect(Primary.copy(alpha = 0.10f), Offset(ch.box.x, ch.box.y), Size(ch.box.w, ch.box.h))
                                    drawRect(Primary, Offset(ch.box.x, ch.box.y), Size(ch.box.w, ch.box.h), style = Stroke(2f / (s * frame.scale)))
                                } else if (isReading) {
                                    drawRect(Primary.copy(alpha = 0.14f), Offset(ch.box.x, ch.box.y), Size(ch.box.w, ch.box.h))
                                    drawRect(Primary, Offset(ch.box.x, ch.box.y), Size(ch.box.w, ch.box.h), style = Stroke(1.6f / (s * frame.scale)))
                                } else {
                                    // 把握度低：模型在多个候选字之间犹豫，用警示色标出
                                    val lowConf = ch.conf < 0.5f
                                    drawRect(
                                        when {
                                            ch.note -> FRAME_NOTE.copy(alpha = alpha)
                                            lowConf -> Warn.copy(alpha = alpha)
                                            else -> FRAME.copy(alpha = alpha)
                                        },
                                        Offset(ch.box.x, ch.box.y),
                                        Size(ch.box.w, ch.box.h),
                                        style = Stroke(if (lowConf) thin * 2.4f else thin),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 字卡浮层：被点字在下半屏 → 顶部抽屉，否则底部
            selected?.let { ch ->
                val r = resolver.resolve(page, ch, mode, store.overrides())
                val s = scale()
                val baseY = frame.offset.y + (ch.box.y + ch.box.h / 2f) * frame.scale
                val screenY = offset.y + baseY * s
                val atTop = screenY > viewport.height * 0.52f
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(if (atTop) 0.dp else 16.dp, if (atTop) 0.dp else 16.dp, 16.dp, 16.dp),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = 330.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.End) {
                            Text("✕", fontSize = 16.sp, color = Ink3, modifier = Modifier.padding(6.dp).clickable {
                                selected = null
                            })
                        }
                        CharCard(
                            char = ch.c,
                            reading = r,
                            info = data.lexicon[ch.c],
                            lex = data.lexicon,
                            settings = settings,
                            formKey = formKey,
                            onFormKey = { formKey = it },
                            inVocab = store.inVocab(ch.c, r.p ?: ""),
                            onAdd = {
                                store.toggleVocab(
                                    VocabItem(
                                        c = ch.c, p = r.p ?: "", z = r.z ?: "",
                                        variant = r.variant ?: "", source = r.source,
                                        ts = System.currentTimeMillis(),
                                    )
                                )
                            },
                            onCorrect = { correctionFor = ch },
                            onSpeakPoly = { p -> player.speak(p.p) },
                            hasAudio = player.pick(r.audioIds) != null,
                            conf = ch.conf,
                        )
                    }
                }
            }
        }

        // 工具条
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolItem(if (mode == "scripture") "旧读" else "今读", "◐", mode == "scripture") {
                modeOverride = if (mode == "scripture") "general" else "scripture"
            }
            ToolItem("注音", "ㄅ", settings.zhuyin) {
                store.update { it.copy(zhuyin = !it.zhuyin) }
            }
            ToolItem(if (readJob != null) "停止" else "朗读", if (readJob != null) "■" else "▶", readJob != null) {
                if (readJob != null) stopRead()
                else startRead(page.readingOrder(settings.skipNotes))
            }
            ToolItem("文本", "文", textView) {
                textView = !textView
                if (textView) selected = null
            }
            if (hasPhoto) {
                ToolItem(if (calibrating) "完成" else "对齐", "⊞", calibrating) {
                    calibrating = !calibrating
                    if (calibrating) {
                        textView = false
                        selected = null
                        stopRead()
                    }
                }
            } else {
                ToolItem("判定", "⋯", false) { }
            }
        }
    }

    correctionFor?.let { ch ->
        val r = resolver.resolve(page, ch, mode, store.overrides())
        AlertDialog(
            onDismissRequest = { correctionFor = null },
            title = { Text("纠正「${ch.c}」的读音", fontSize = 15.sp) },
            text = {
                Column {
                    Text("只影响这一个字，存在本机；不改动原文。", fontSize = 12.sp, color = Ink2)
                    Spacer(Modifier.height(8.dp))
                    r.readings.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                store.setCorrection(ch.c, c.variant)
                                correctionFor = null
                                speakOne(ch)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.p, fontSize = 14.sp, color = Ink)
                            Spacer(Modifier.width(8.dp))
                            Tag(c.label.ifBlank { "读音" }, c.label == "旧读")
                            Spacer(Modifier.weight(1f))
                            Text(if (r.p == c.p) "当前" else "改为", fontSize = 12.sp, color = Ink3)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.clearCorrection(ch.c)
                    correctionFor = null
                }) { Text("恢复自动判定", fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { correctionFor = null }) { Text("取消", fontSize = 12.sp) }
            },
        )
    }
}

@Composable
private fun RowScope.ToolItem(label: String, icon: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable { onClick() }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 15.sp, color = if (active) Primary else Ink2)
        Text(label, fontSize = 11.sp, color = if (active) Primary else Ink2)
    }
}

/** 文本视图：无障碍替代入口，逐字可聚焦、aria 由 TalkBack 读 pinyin */
@Composable
private fun TextView(
    page: com.readtool.domain.PageModel,
    resolver: ReadingResolver,
    mode: String,
    store: Store,
    onTap: (CharNode) -> Unit,
) {
    val lines = page.lines.filter { ln -> ln.chars.any { !it.punct } }
    LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
        items(lines) { ln ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                ln.chars.filter { !it.punct }.forEach { ch ->
                    val r = resolver.resolve(page, ch, mode, store.overrides())
                    Column(
                        Modifier.padding(end = 4.dp).clickable { onTap(ch) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ch.c, fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink)
                        Text(r.z ?: r.p ?: "", fontSize = 9.sp, color = Ink3)
                    }
                }
            }
        }
    }
}
