package com.readtool.app.data

import android.content.res.AssetManager
import com.readtool.domain.CharDiff
import com.readtool.domain.CharInfo
import com.readtool.domain.ContextTerms
import com.readtool.domain.Lexicon
import com.readtool.domain.PolyReading
import com.readtool.domain.Readings
import com.readtool.domain.Variant
import com.readtool.domain.WordEntry
import org.json.JSONObject

data class AudioItem(val file: String, val ms: Int, val bytes: Int)

/** 一页的排版参数（同时也是历史记录里可重建的最小数据） */
data class PageSpec(
    val id: String,
    val title: String,
    val kind: String,
    val vertical: Boolean,
    val text: String,
    val note: String = "",
    val cols: Int = 5,
    val charsPerCol: Int = 14,
    val fontSize: Int = 96,
    val lineChars: Int = 18,
    /** 拍照/相册导入的照片路径；为空表示纯样张页 */
    val imagePath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("kind", kind).put("vertical", vertical)
        .put("text", text).put("note", note).put("cols", cols)
        .put("charsPerCol", charsPerCol).put("fontSize", fontSize).put("lineChars", lineChars)
        .put("imagePath", imagePath ?: "")

    companion object {
        fun from(o: JSONObject) = PageSpec(
            id = o.optString("id", "page"),
            title = o.optString("title", "未命名页"),
            kind = o.optString("kind", "样张"),
            vertical = o.optBoolean("vertical", true),
            text = o.optString("text", ""),
            note = o.optString("note", ""),
            cols = o.optInt("cols", 5),
            charsPerCol = o.optInt("charsPerCol", 14),
            fontSize = o.optInt("fontSize", 96),
            lineChars = o.optInt("lineChars", 18),
            imagePath = o.optString("imagePath").ifBlank { null },
        )
    }
}

class AppData(
    val readings: Readings,
    val lexicon: Lexicon,
    val terms: ContextTerms,
    val fallback: Map<String, String>,
    val audio: Map<String, AudioItem>,
    val audioVersion: String,
    val audioDraft: Boolean,
    val demos: List<PageSpec>,
) {
    val diffWordCount: Int get() = readings.words.count { it.diff }
}

private fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

object Assets {
    fun load(am: AssetManager): AppData {
        val t0 = System.currentTimeMillis()
        val readings = parseReadings(read(am, "data/readings.json"))
        val t1 = System.currentTimeMillis()
        val lexicon = parseLexicon(read(am, "data/lexicon.json"))
        val t2 = System.currentTimeMillis()
        val terms = parseTerms(read(am, "data/context-terms.json"))
        val fallback = parseFallback(read(am, "data/pinyin.json"))
        val t3 = System.currentTimeMillis()
        val audioJson = JSONObject(read(am, "data/audio-index.json"))
        val audio = parseAudio(audioJson)
        val t4 = System.currentTimeMillis()
        val demos = parseDemos(read(am, "data/demo.json"))
        val t5 = System.currentTimeMillis()
        android.util.Log.i(
            "AssetsLoad",
            "readings=${t1 - t0}ms lexicon=${t2 - t1}ms terms+fallback=${t3 - t2}ms audio=${t4 - t3}ms demos=${t5 - t4}ms total=${t5 - t0}ms" +
                " (lex=${lexicon.chars.size} audio=${audio.size} fallback=${fallback.size})",
        )
        return AppData(
            readings = readings,
            lexicon = lexicon,
            terms = terms,
            fallback = fallback,
            audio = audio,
            audioVersion = audioJson.optString("version", "—"),
            audioDraft = audioJson.optString("status") == "draft-tts",
            demos = demos,
        )
    }

    private fun read(am: AssetManager, path: String): String =
        am.open(path).bufferedReader().use { it.readText() }

    private fun parseReadings(text: String): Readings {
        val root = JSONObject(text)
        val words = mutableListOf<WordEntry>()
        val wa = root.optJSONArray("words") ?: org.json.JSONArray()
        for (i in 0 until wa.length()) {
            val o = wa.getJSONObject(i)
            words.add(
                WordEntry(
                    w = o.getString("w"),
                    old = o.optString("old"),
                    modern = o.optString("modern"),
                    src = o.optString("src"),
                )
            )
        }
        val chars = mutableMapOf<String, CharDiff>()
        root.optJSONObject("chars")?.let { obj ->
            for (key in obj.keys()) {
                val o = obj.getJSONObject(key)
                val oldO = o.optJSONObject("old") ?: JSONObject()
                val modO = o.optJSONObject("modern") ?: JSONObject()
                chars[key] = CharDiff(
                    old = Variant(oldO.optString("p"), oldO.str("z")),
                    modern = Variant(modO.optString("p"), modO.str("z")),
                    src = o.optString("src"),
                )
            }
        }
        val poly = mutableMapOf<String, List<PolyReading>>()
        root.optJSONObject("polyphones")?.let { obj ->
            for (key in obj.keys()) {
                val arr = obj.getJSONObject(key).optJSONArray("readings") ?: org.json.JSONArray()
                val list = mutableListOf<PolyReading>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(PolyReading(o.optString("p"), o.str("z")))
                }
                poly[key] = list
            }
        }
        return Readings(words, chars, poly)
    }

    private fun parseLexicon(text: String): Lexicon {
        val root = JSONObject(text).optJSONObject("chars") ?: JSONObject()
        val map = HashMap<String, CharInfo>(root.length())
        for (key in root.keys()) {
            val o = root.getJSONObject(key)
            val zy = HashMap<String, String>()
            o.optJSONObject("zy")?.let { z ->
                for (k in z.keys()) zy[k] = z.getString(k)
            }
            map[key] = CharInfo(
                p = o.str("p"),
                z = o.str("z"),
                old = o.optJSONObject("o")?.let { Variant(it.optString("p"), it.str("z")) },
                modern = o.optJSONObject("m")?.let { Variant(it.optString("p"), it.str("z")) },
                poly = (o.optJSONArray("poly") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).map {
                        val r = arr.getJSONObject(it)
                        PolyReading(r.optString("p"), r.str("z"))
                    }
                },
                polyNote = o.str("polyNote"),
                rad = o.str("rad"),
                strk = if (o.has("strk") && !o.isNull("strk")) o.optInt("strk") else null,
                struct = o.str("struct"),
                trad = (o.optJSONArray("trad") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                simp = o.str("simp"),
                vari = (o.optJSONArray("vari") ?: org.json.JSONArray()).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                cp = o.str("cp"),
                zy = zy,
            )
        }
        return Lexicon(map)
    }

    private fun parseTerms(text: String): ContextTerms {
        val o = JSONObject(text)
        fun arr(name: String): List<String> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }
        return ContextTerms(arr("ritual"), arr("formulas"), arr("negative"), arr("modernMarkers"))
    }

    private fun parseFallback(text: String): Map<String, String> {
        val o = JSONObject(text)
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.getString(k)
        return out
    }

    private fun parseAudio(root: JSONObject): Map<String, AudioItem> {
        val items = root.optJSONObject("items") ?: JSONObject()
        val out = HashMap<String, AudioItem>(items.length())
        for (k in items.keys()) {
            val o = items.getJSONObject(k)
            out[k] = AudioItem(o.optString("f"), o.optInt("ms", 400), o.optInt("b", 0))
        }
        return out
    }

    private fun parseDemos(text: String): List<PageSpec> {
        val pages = JSONObject(text).optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).map { i ->
            val o = pages.getJSONObject(i)
            PageSpec(
                id = o.optString("id"),
                title = o.optString("title"),
                kind = o.optString("kind"),
                vertical = o.optBoolean("vertical", true),
                text = o.optString("text"),
                note = o.optString("note"),
                cols = o.optInt("cols", 5),
                charsPerCol = o.optInt("charsPerCol", 14),
                fontSize = o.optInt("fontSize", 96),
                lineChars = o.optInt("lineChars", 18),
            )
        }
    }
}
