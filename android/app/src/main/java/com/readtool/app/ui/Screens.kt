package com.readtool.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readtool.app.data.AppData
import com.readtool.app.data.HistoryItem
import com.readtool.app.data.PageSpec
import com.readtool.app.data.Store
import java.io.File
import kotlin.math.roundToInt

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) {
                Text("←", fontSize = 18.sp, color = Ink2, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            }
        }
        Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 15.sp, color = Ink)
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { action?.invoke() }
    }
}

/* ---------------- 首页 ---------------- */
@Composable
fun HomeScreen(
    store: Store,
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onLookup: (String) -> Unit,
    onVocab: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("识字", fontFamily = FontFamily.Serif, fontSize = 34.sp, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "拍下你手上的那页纸，逐字点选、听读音。",
            fontSize = 13.sp, color = Ink2, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("拍照识字", onCapture, height = 60)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("查一个字，如 般、耨、槃", fontSize = 13.sp, color = Ink3) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (query.isNotBlank()) onLookup(query.trim()) }) {
                Text("查", fontSize = 15.sp, color = Primary)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickItem("相册", "导入图片", Modifier.weight(1f), onPickImage)
            QuickItem("生字本", "${store.vocab.size} 字", Modifier.weight(1f), onVocab)
            QuickItem("历史", "${store.history.size} 页", Modifier.weight(1f), onHistory)
            QuickItem("设置", "读音与语音", Modifier.weight(1f), onSettings)
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun QuickItem(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, LineC, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 13.sp, color = Ink)
        Text(sub, fontSize = 11.sp, color = Ink3)
    }
}

/* ---------------- 采集 / 录入 ---------------- */
@Composable
fun CaptureScreen(data: AppData, imagePath: String?, onBack: () -> Unit, onBuild: (PageSpec) -> Unit) {
    var text by remember { mutableStateOf("") }
    var vertical by remember { mutableStateOf(true) }
    var withNote by remember { mutableStateOf(true) }
    val shotBitmap = imagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        TopBar("开始一页", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("拍照 / 导入", fontSize = 13.sp, color = Ink)
            if (shotBitmap != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(220.dp)
                        .background(Paper, RoundedCornerShape(8.dp))
                        .border(1.dp, LineC, RoundedCornerShape(8.dp)),
                ) {
                    Image(
                        shotBitmap.asImageBitmap(),
                        contentDescription = "本页照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    "照片已导入（仅本机处理）。当前识别引擎尚未接入，请对照照片录入本页文字生成字框；进入结果页后点“对齐”可拖动字框贴合照片。",
                    fontSize = 12.sp, color = Ink2, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(
                    "图片只在本机处理，不上传。当前版本识别引擎尚未接入（Android 侧将接 PP-OCR），导入图片后需要录入本页文字来生成字框。",
                    fontSize = 12.sp, color = Ink2, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Text("录入本页文字", fontSize = 13.sp, color = Ink, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("把这一页的字打进来，标点与空格不影响取字。", fontSize = 12.sp, color = Ink3) },
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckRow("竖排右起", vertical) { vertical = true }
                CheckRow("横排", !vertical) { vertical = false }
                Spacer(Modifier.weight(1f))
                Checkbox(checked = withNote, onCheckedChange = { withNote = it })
                Text("加双行夹注", fontSize = 12.sp, color = Ink2)
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("生成字框", {
                if (text.trim().length < 2) return@PrimaryButton
                onBuild(
                    PageSpec(
                        id = "manual-" + System.currentTimeMillis(),
                        title = "录入页",
                        kind = if (vertical) "竖排录入页" else "横排录入页",
                        vertical = vertical,
                        text = text.trim(),
                        note = if (withNote) "注：本页字框由录入文字生成，用于校对读音。" else "",
                        fontSize = if (vertical) 88 else 44,
                        cols = 5,
                        charsPerCol = 15,
                        lineChars = 18,
                        imagePath = imagePath,
                    )
                )
            }, height = 48)

            Spacer(Modifier.height(26.dp))
            Text("自带样张", fontSize = 13.sp, color = Ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.demos.forEach { spec ->
                    Column(
                        Modifier.weight(1f)
                            .background(Paper, RoundedCornerShape(8.dp))
                            .border(1.dp, LineC, RoundedCornerShape(8.dp))
                            .clickable { onBuild(spec) }
                            .padding(10.dp),
                    ) {
                        Text(if (spec.vertical) "竖排字表" else "横排说明文", fontSize = 12.sp, color = Ink)
                        Text(spec.kind, fontSize = 11.sp, color = Ink3)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onChange() })
        Text(label, fontSize = 12.sp, color = Ink2)
    }
}

/* ---------------- 生字本 ---------------- */
@Composable
fun VocabScreen(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("生字本", onBack, action = {
            Text("导出", fontSize = 13.sp, color = Ink2, modifier = Modifier.clickable {
                if (store.vocab.isEmpty()) return@clickable
                val sb = StringBuilder("字,拼音,注音,读音来源,依据\n")
                store.vocab.forEach { v ->
                    sb.append("\"${v.c}\",\"${v.p}\",\"${v.z}\",\"${if (v.variant == "old") "旧读" else "今读"}\",\"${v.source}\"\n")
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "生字本")
                    putExtra(Intent.EXTRA_TEXT, sb.toString())
                }
                context.startActivity(Intent.createChooser(intent, "导出到"))
            }.padding(8.dp))
        })
        if (store.vocab.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                Text("生字本还是空的。", fontSize = 14.sp, color = Ink2)
                Text("点开一页，点中任何一个字；或在首页查字后收进来。", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(store.vocab.size) { i ->
                val v = store.vocab[i]
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bmp = v.thumb?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                    Box(
                        Modifier.size(56.dp).background(Paper, RoundedCornerShape(6.dp))
                            .border(1.dp, LineC, RoundedCornerShape(6.dp)),
                    ) {
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v.c, fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink)
                            Spacer(Modifier.width(8.dp))
                            Text(v.p, fontSize = 13.sp, color = Primary)
                            if (v.z.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(v.z, fontSize = 11.sp, color = Ink3)
                            }
                        }
                        Text(
                            listOfNotNull(
                                if (v.variant == "old") "旧读" else "今读",
                                v.source.ifBlank { null },
                            ).joinToString(" · "),
                            fontSize = 11.sp, color = Ink3, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text("删除", fontSize = 12.sp, color = Ink3, modifier = Modifier.clickable { store.removeVocab(i) }.padding(8.dp))
                }
            }
        }
    }
}

/* ---------------- 历史 ---------------- */
@Composable
fun HistoryScreen(store: Store, onBack: () -> Unit, onOpen: (PageSpec) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("历史", onBack, action = {
            Text("清空", fontSize = 13.sp, color = Ink2, modifier = Modifier.clickable {
                store.clearHistory()
            }.padding(8.dp))
        })
        if (store.history.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                Text("还没有看过任何一页。", fontSize = 14.sp, color = Ink2)
                Text("拍一页或打开自带样张，就会记在这里。", fontSize = 12.sp, color = Ink3, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(store.history.size) { i ->
                val h: HistoryItem = store.history[i]
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(h.spec) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).background(Paper, RoundedCornerShape(6.dp))
                            .border(1.dp, LineC, RoundedCornerShape(6.dp)),
                    ) {
                        Text("页", fontSize = 14.sp, color = Ink3, modifier = Modifier.align(Alignment.Center))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, fontSize = 13.sp, color = Ink)
                        Text(
                            listOf(h.kind, "${h.chars} 字").joinToString(" · "),
                            fontSize = 11.sp, color = Ink3, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 设置 ---------------- */
@Composable
fun SettingsScreen(data: AppData, store: Store, onBack: () -> Unit) {
    val s = store.settings
    Column(Modifier.fillMaxSize()) {
        TopBar("设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            GroupTitle("读音")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("默认读音优先", fontSize = 13.sp, color = Ink)
                    Text("普通页按今读；判定为诵读页才升为旧读", fontSize = 11.sp, color = Ink3)
                }
                SegButton("今读", s.defaultMode == "general") { store.update { it.copy(defaultMode = "general") } }
                Spacer(Modifier.width(6.dp))
                SegButton("旧读", s.defaultMode == "scripture") { store.update { it.copy(defaultMode = "scripture") } }
            }
            SwitchRow("显示注音符号", "字卡内同时显示 ㄅㄆㄇ", s.zhuyin) { store.update { it.copy(zhuyin = !it.zhuyin) } }
            SwitchRow("朗读跳过注文", "双行小字默认不读", s.skipNotes) { store.update { it.copy(skipNotes = !it.skipNotes) } }
            SwitchRow("隐藏双行注文", "整段收起小字夹注，原图仍在", s.hideNotes) { store.update { it.copy(hideNotes = !it.hideNotes) } }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("朗读语速", fontSize = 13.sp, color = Ink)
                    Text("0.85x 更接近旧读节奏，调速立即生效", fontSize = 11.sp, color = Ink3)
                }
                Text("${(s.readRate * 100).roundToInt() / 100f}x", fontSize = 12.sp, color = Ink2)
            }
            Slider(
                value = s.readRate,
                onValueChange = { store.update { st -> st.copy(readRate = it) } },
                valueRange = 0.5f..1.5f,
            )

            Spacer(Modifier.height(16.dp))
            GroupTitle("数据与语音")
            InfoRow("发音包", "${data.audio.size} 条 · 版本 ${data.audioVersion}", if (data.audioDraft) "草稿音" else "正式")
            InfoRow("读音差异集", "${data.readings.words.size} 条词条 · ${data.readings.chars.size} 个差异字", data.readings.words.size.toString())
            Text(
                "当前发音为 TTS 草稿音，多音字用同音字串控制读音，未经审定，不能作为发版读音。",
                fontSize = 11.sp, color = Ink3, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
            GroupTitle("本机数据")
            InfoRow("纠错记录", "${store.correctionCount()} 个字的读音被你改过", "")
            TextButton(onClick = { store.clearCorrections() }) { Text("清空纠错", fontSize = 12.sp, color = Ink2) }
            InfoRow("历史记录", "${store.history.size} 条", "")
            TextButton(onClick = { store.clearHistory() }) { Text("清空历史", fontSize = 12.sp, color = Ink2) }
            TextButton(onClick = { store.clearAll() }) { Text("清除全部本机数据", fontSize = 12.sp, color = Color(0xFFA33333)) }

            Spacer(Modifier.height(16.dp))
            GroupTitle("关于")
            Text(
                "识字是一个离线识字工具：拍下你自己的一页纸，逐字点选听读音；也可以直接查单个字。",
                fontSize = 12.sp, color = Ink2, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, fontSize = 13.sp, color = Ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Ink)
            Text(sub, fontSize = 11.sp, color = Ink3)
        }
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun SegButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Primary else Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, LineC, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 12.sp, color = if (selected) Color.White else Ink2)
    }
}

@Composable
private fun InfoRow(title: String, sub: String, badge: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Ink)
            Text(sub, fontSize = 11.sp, color = Ink3)
        }
        if (badge.isNotBlank()) Text(badge, fontSize = 11.sp, color = Ink3)
    }
}
