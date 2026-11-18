package com.readtool.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readtool.app.audio.SoundPlayer
import com.readtool.app.data.AppData
import com.readtool.app.data.Store
import com.readtool.app.data.VocabItem
import com.readtool.domain.Block
import com.readtool.domain.CharNode
import com.readtool.domain.CharRect
import com.readtool.domain.PageModel
import com.readtool.domain.ReadingResolver
import com.readtool.domain.ReadingResult
import kotlinx.coroutines.launch

/** 造一个只有一列的临时页面，让词条匹配照常生效（输入「般若」→ bō rě） */
private fun syntheticPage(chars: List<Char>): PageModel {
    val nodes = chars.mapIndexed { i, c ->
        CharNode(i, c.toString(), "body", 0, i, CharRect(0f, i * 10f, 10f, 10f))
    }
    val page = PageModel(true, listOf(Block("body", "body", "v")), nodes)
    page.buildLines()
    return page
}

@Composable
fun LookupScreen(
    data: AppData,
    store: Store,
    player: SoundPlayer,
    seed: String,
    onBack: () -> Unit,
) {
    val resolver = remember(data) { ReadingResolver(data.readings, data.lexicon, data.fallback) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(seed) }
    var results by remember { mutableStateOf<List<Pair<CharNode, ReadingResult>>>(emptyList()) }
    var formKeys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun run(q: String) {
        val chars = q.toList().filter { it in '\u3400'..'\u9FFF' || it in '\uF900'..'\uFAFF' }
        if (chars.isEmpty()) {
            results = emptyList()
            return
        }
        val page = syntheticPage(chars)
        val mode = if (store.settings.defaultMode == "scripture") "scripture" else "general"
        results = chars.distinct().mapNotNull { c ->
            val node = page.chars.firstOrNull { it.c == c.toString() } ?: return@mapNotNull null
            node to resolver.resolve(page, node, mode, store.overrides())
        }
    }

    LaunchedEffect(seed) { if (seed.isNotBlank()) run(seed) }

    Column(Modifier.fillMaxSize()) {
        TopBar("查字", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("如 般、般若、耨", fontSize = 13.sp, color = Ink3) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { run(query.trim()) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) { Text("查", color = Color.White, fontSize = 14.sp) }
            }
            Text(
                "只查字形与读音，不做释义。输入多个字按词条匹配（如「般若」→ bō rě）。",
                fontSize = 11.sp, color = Ink3, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 8.dp),
            )

            if (results.isEmpty()) {
                Text("输入一个字或词开始查。", fontSize = 13.sp, color = Ink2, modifier = Modifier.padding(vertical = 24.dp))
            }

            results.forEach { (node, r) ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, LineC, RoundedCornerShape(12.dp)),
                ) {
                    CharCard(
                        char = node.c,
                        reading = r,
                        info = data.lexicon[node.c],
                        lex = data.lexicon,
                        settings = store.settings,
                        formKey = formKeys[node.c] ?: "origin",
                        onFormKey = { formKeys = formKeys + (node.c to it) },
                        inVocab = store.inVocab(node.c, r.p ?: ""),
                        onAdd = {
                            store.toggleVocab(
                                VocabItem(
                                    c = node.c, p = r.p ?: "", z = r.z ?: "",
                                    variant = r.variant ?: "", source = r.source,
                                    ts = System.currentTimeMillis(),
                                )
                            )
                        },
                        onSpeakPoly = { p -> player.speak(p.p) },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val ms = player.play(r.audioIds, 1f)
                                    if (ms <= 0) player.speak(r.p ?: node.c)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        ) { Text("播放读音", color = Color.White, fontSize = 14.sp) }
                    }
                }
            }

            if (results.isEmpty() && query.isNotBlank()) {
                TextButton(onClick = { run(query.trim()) }) { Text("再查一次", fontSize = 12.sp, color = Primary) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
