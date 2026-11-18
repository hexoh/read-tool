package com.readtool.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.util.Log
import com.readtool.app.data.AudioItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 发音：SoundPool 播短音频（预解码、连点打断），失败走系统 TTS 兜底。
 * 音频索引契约见 docs/audio-pack.md：w:{词}:{old|modern|any} / c:{字}:{old|modern|any}
 */
class SoundPlayer(
    private val context: Context,
    private val index: Map<String, AudioItem>,
) {
    private val TAG = "SoundPlayer"

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val soundIds = HashMap<String, Int>()
    private val pending = HashMap<Int, CompletableDeferred<Unit>>()
    private var lastStream = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, Float>? = null

    init {
        pool.setOnLoadCompleteListener { _, sampleId, _ ->
            pending.remove(sampleId)?.complete(Unit)
        }
    }

    fun has(id: String): Boolean = index.containsKey(id)

    fun pick(candidates: List<String>): String? = candidates.firstOrNull { index.containsKey(it) }

    private suspend fun soundId(path: String): Int? {
        soundIds[path]?.let { return it }
        val fd = runCatching { context.assets.openFd(path) }.getOrNull()
        if (fd == null) {
            Log.w(TAG, "openFd failed: $path")
            return null
        }
        val deferred = CompletableDeferred<Unit>()
        val sid = runCatching { pool.load(fd, 1) }.getOrElse {
            fd.close()
            Log.w(TAG, "SoundPool.load threw: $path ${it.message}")
            return null
        }
        fd.close()
        pending[sid] = deferred
        val loaded = withTimeoutOrNull(1500) { deferred.await() }
        if (loaded == null) Log.w(TAG, "load timeout(1.5s): $path")
        soundIds[path] = sid
        return sid
    }

    /** 预加载（出框后预热前若干字） */
    fun preload(ids: List<String>) {
        for (id in ids) {
            val item = index[id] ?: continue
            val path = "audio/" + item.file
            if (soundIds.containsKey(path)) continue
            runCatching {
                context.assets.openFd(path).use { fd ->
                    val sid = pool.load(fd, 1)
                    soundIds[path] = sid
                }
            }
        }
    }

    fun stop() {
        if (lastStream != 0) runCatching { pool.stop(lastStream) }
        lastStream = 0
    }

    /** 播放一条；返回时长（毫秒），0 表示没有可用音频 */
    suspend fun play(candidates: List<String>, rate: Float = 1f): Int {
        val id = pick(candidates) ?: run {
            Log.w(TAG, "no audio id matched: $candidates")
            return 0
        }
        val item = index.getValue(id)
        val sid = soundId("audio/" + item.file) ?: return 0
        stop()
        val r = rate.coerceIn(0.5f, 2f)
        lastStream = pool.play(sid, 1f, 1f, 1, 0, r)
        if (lastStream == 0) Log.w(TAG, "SoundPool.play returned 0 (sid=$sid, id=$id)")
        return (item.ms / r).toInt().coerceAtLeast(150)
    }

    /** 系统 TTS 兜底：国内机型中文引擎可能缺失，全部走 runCatching */
    fun speak(text: String, rate: Float = 0.85f) {
        if (text.isBlank()) return
        if (tts == null) {
            tts = runCatching {
                TextToSpeech(context) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        runCatching { tts?.language = Locale.CHINA }
                        // 初始化是异步的：把等待中的首次发音补上（否则首次 speak 必然失败）
                        pendingSpeak?.let { (t, r) ->
                            pendingSpeak = null
                            speakNow(t, r)
                        }
                    } else {
                        Log.w(TAG, "TTS init failed (no engine on this device?)")
                    }
                }
            }.getOrNull()
        }
        if (!ttsReady) {
            // 引擎还没就绪，先挂起，等 onInit 回调后补播
            pendingSpeak = text to rate
            return
        }
        speakNow(text, rate)
    }

    private fun speakNow(text: String, rate: Float) {
        runCatching {
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "readtool")
        }.onFailure { Log.w(TAG, "tts speak failed: ${it.message}") }
    }

    fun release() {
        runCatching { pool.release() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
