package com.readtool.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val defaultMode: String = "general",
    val zhuyin: Boolean = true,
    val skipNotes: Boolean = true,
    val hideNotes: Boolean = false,
    val readRate: Float = 0.85f,
)

data class VocabItem(
    val c: String,
    val p: String,
    val z: String,
    val variant: String,
    val source: String,
    val ts: Long,
    val thumb: String? = null,
)

data class HistoryItem(
    val title: String,
    val kind: String,
    val chars: Int,
    val ts: Long,
    val spec: PageSpec,
)

/** 本机存储：设置 / 纠错 / 生字本 / 历史（与网页端 localStorage 同构） */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("readtool", Context.MODE_PRIVATE)

    var settings by mutableStateOf(loadSettings())
        private set

    private val corrections: MutableMap<String, String> = loadCorrections()

    var vocab by mutableStateOf(loadVocab())
        private set

    var history by mutableStateOf(loadHistory())
        private set

    fun update(block: (Settings) -> Settings) {
        settings = block(settings)
        prefs.edit()
            .putString("settings", JSONObject()
                .put("defaultMode", settings.defaultMode)
                .put("zhuyin", settings.zhuyin)
                .put("skipNotes", settings.skipNotes)
                .put("hideNotes", settings.hideNotes)
                .put("readRate", settings.readRate.toDouble())
                .toString())
            .apply()
    }

    fun overrides(): Map<String, String> = HashMap(corrections)

    fun setCorrection(c: String, variant: String) {
        corrections[c] = variant
        saveCorrections()
    }

    fun clearCorrection(c: String) {
        corrections.remove(c)
        saveCorrections()
    }

    fun correctionCount(): Int = corrections.size

    fun clearCorrections() {
        corrections.clear()
        saveCorrections()
    }

    /** 返回 true 表示已加入，false 表示已移除 */
    fun toggleVocab(item: VocabItem): Boolean {
        val existing = vocab.indexOfFirst { it.c == item.c && it.p == item.p }
        vocab = if (existing >= 0) {
            vocab.filterIndexed { i, _ -> i != existing }
        } else {
            listOf(item) + vocab
        }
        saveVocab()
        return existing < 0
    }

    fun removeVocab(index: Int) {
        vocab = vocab.filterIndexed { i, _ -> i != index }
        saveVocab()
    }

    fun inVocab(c: String, p: String): Boolean = vocab.any { it.c == c && it.p == p }

    fun pushHistory(spec: PageSpec, chars: Int) {
        history = (listOf(HistoryItem(spec.title, spec.kind, chars, System.currentTimeMillis(), spec)) + history)
            .take(30)
        saveHistory()
    }

    fun clearHistory() {
        history = emptyList()
        saveHistory()
    }

    fun clearAll() {
        vocab = emptyList()
        history = emptyList()
        corrections.clear()
        settings = Settings()
        prefs.edit().clear().apply()
    }

    // ---------- 持久化 ----------
    private fun loadSettings(): Settings {
        val raw = prefs.getString("settings", null) ?: return Settings()
        return try {
            val o = JSONObject(raw)
            Settings(
                defaultMode = o.optString("defaultMode", "general"),
                zhuyin = o.optBoolean("zhuyin", true),
                skipNotes = o.optBoolean("skipNotes", true),
                hideNotes = o.optBoolean("hideNotes", false),
                readRate = o.optDouble("readRate", 0.85).toFloat(),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    private fun loadCorrections(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        val raw = prefs.getString("corrections", null) ?: return out
        runCatching {
            val o = JSONObject(raw)
            for (k in o.keys()) out[k] = o.getString(k)
        }
        return out
    }

    private fun saveCorrections() {
        val o = JSONObject()
        corrections.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("corrections", o.toString()).apply()
    }

    private fun loadVocab(): List<VocabItem> {
        val raw = prefs.getString("vocab", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VocabItem(
                    c = o.optString("c"), p = o.optString("p"), z = o.optString("z"),
                    variant = o.optString("variant"), source = o.optString("source"),
                    ts = o.optLong("ts"), thumb = o.optString("thumb").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveVocab() {
        val arr = JSONArray()
        vocab.forEach { v ->
            arr.put(JSONObject()
                .put("c", v.c).put("p", v.p).put("z", v.z)
                .put("variant", v.variant).put("source", v.source)
                .put("ts", v.ts).put("thumb", v.thumb ?: ""))
        }
        prefs.edit().putString("vocab", arr.toString()).apply()
    }

    private fun loadHistory(): List<HistoryItem> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val spec = o.optJSONObject("spec") ?: return@mapNotNull null
                HistoryItem(
                    title = o.optString("title"),
                    kind = o.optString("kind"),
                    chars = o.optInt("chars"),
                    ts = o.optLong("ts"),
                    spec = PageSpec.from(spec),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach { h ->
            arr.put(JSONObject()
                .put("title", h.title).put("kind", h.kind)
                .put("chars", h.chars).put("ts", h.ts)
                .put("spec", h.spec.toJson()))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
