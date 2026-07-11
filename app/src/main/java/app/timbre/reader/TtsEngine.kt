package app.timbre.reader

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.text.BreakIterator
import java.util.Locale

/**
 * Wraps Android's on-device TextToSpeech with:
 *  - sentence chunking (BreakIterator), so long text works and we can
 *    pause/resume/seek by sentence (Android TTS has no native pause);
 *  - global character offsets for karaoke highlighting via onRangeStart;
 *  - simple voice enumeration with friendly labels.
 *
 * TTS progress callbacks arrive on a binder thread; this class posts them to
 * the main thread, so all engine state is main-thread-confined and Listener
 * methods are always invoked on main.
 */
class TtsEngine(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** Engine finished init; voices are available. */
        fun onReady()
        /** A sentence began: [start, end) are offsets into the full text. */
        fun onSentenceStart(sentenceIndex: Int, start: Int, end: Int)
        /** A word is being spoken: [start, end) offsets into the full text. */
        fun onWord(start: Int, end: Int)
        /** Playback reached the end of the text (not called on stop/pause). */
        fun onFinished()
        fun onError(message: String)
    }

    data class Sentence(val start: Int, val end: Int) // [start, end) in full text

    private var tts: TextToSpeech? = null
    private var ready = false

    private var fullText: String = ""
    private var sentences: List<Sentence> = emptyList()

    /** Index of the sentence currently being spoken (or paused at). */
    var currentSentence: Int = 0
        private set
    var isSpeaking: Boolean = false
        private set
    var isPaused: Boolean = false
        private set

    val textLength: Int get() = fullText.length
    val sentenceCount: Int get() = sentences.size

    /** Generation counter: stale utterance callbacks are ignored after stop/seek. */
    private var generation = 0

    /** Sentences are enqueued in windows to avoid stalling the main thread on
     *  huge texts; topped up from onDone as playback approaches the edge. */
    private val windowSize = 50
    private var enqueuedUpTo = 0 // exclusive sentence index

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(progressListener)
                listener.onReady()
            } else {
                listener.onError("Text-to-speech engine failed to start")
            }
        }
    }

    /* ------------------------------ voices ------------------------------ */

    data class VoiceInfo(val voice: Voice?, val label: String)

    /** Installed (non-network) voices, grouped by locale, friendly labels. */
    fun availableVoices(): List<VoiceInfo> {
        val out = mutableListOf(VoiceInfo(null, "System default"))
        val engine = tts ?: return out
        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (_: Exception) {
            emptySet() // some OEM engines throw here
        }
        val locals = voices
            .filter {
                !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
        val counters = mutableMapOf<String, Int>()
        for (v in locals) {
            val tag = v.locale.getDisplayName(Locale.getDefault())
            val n = (counters[tag] ?: 0) + 1
            counters[tag] = n
            out.add(VoiceInfo(v, "$tag · Voice ${toRoman(n)}"))
        }
        return out
    }

    private fun toRoman(n: Int): String {
        val romans = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        return if (n in 1..10) romans[n - 1] else n.toString()
    }

    fun setVoice(voice: Voice?) {
        val engine = tts ?: return
        try {
            if (voice != null) engine.voice = voice else engine.voice = engine.defaultVoice
        } catch (_: Exception) { /* keep current voice */ }
    }

    fun findVoiceByName(name: String?): Voice? {
        if (name.isNullOrEmpty()) return null
        return try {
            tts?.voices?.firstOrNull { it.name == name }
        } catch (_: Exception) { null }
    }

    fun setRate(rate: Float) { tts?.setSpeechRate(rate.coerceIn(0.5f, 3.0f)) }
    fun setPitch(pitch: Float) { tts?.setPitch(pitch.coerceIn(0.5f, 2.0f)) }

    /* ------------------------------ text ------------------------------ */

    /** Load new text; stops any current playback. */
    fun setText(text: String) {
        stop()
        fullText = text
        sentences = splitSentences(text)
        currentSentence = 0
    }

    private fun splitSentences(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val max = maxOf(500, TextToSpeech.getMaxSpeechInputLength() - 100)
        val out = mutableListOf<Sentence>()
        val it = BreakIterator.getSentenceInstance(Locale.getDefault())
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            var s = start
            // hard-split pathological sentences longer than the engine limit
            while (end - s > max) {
                out.add(Sentence(s, s + max))
                s += max
            }
            if (end > s && text.substring(s, end).isNotBlank()) out.add(Sentence(s, end))
            start = end
            end = it.next()
        }
        return out
    }

    fun sentenceAtChar(char: Int): Int {
        if (sentences.isEmpty()) return 0
        sentences.forEachIndexed { i, s -> if (char < s.end) return i }
        return sentences.size - 1
    }

    /* ---------------------------- transport ---------------------------- */

    fun playFrom(sentenceIndex: Int) {
        val engine = tts ?: return
        if (!ready || sentences.isEmpty()) return
        val from = sentenceIndex.coerceIn(0, sentences.size - 1)

        engine.stop()
        generation++
        currentSentence = from
        isSpeaking = true
        isPaused = false
        enqueueWindow(generation, from, flushFirst = true)
    }

    private fun enqueueWindow(gen: Int, from: Int, flushFirst: Boolean) {
        val engine = tts ?: return
        val to = minOf(sentences.size, from + windowSize)
        for (i in from until to) {
            val s = sentences[i]
            val mode =
                if (i == from && flushFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(fullText.substring(s.start, s.end), mode, Bundle(), utteranceId(gen, i))
        }
        enqueuedUpTo = to
    }

    fun play() = playFrom(currentSentence)

    /** Android TTS cannot truly pause; we stop and remember the sentence. */
    fun pause() {
        if (!isSpeaking) return
        generation++            // invalidate queued utterance callbacks
        tts?.stop()
        isSpeaking = false
        isPaused = true
    }

    fun resume() {
        if (isPaused) playFrom(currentSentence)
    }

    fun stop() {
        generation++
        tts?.stop()
        isSpeaking = false
        isPaused = false
        currentSentence = 0
    }

    fun previousSentence() {
        if (sentences.isEmpty()) return
        val target = if (isSpeaking || isPaused) (currentSentence - 1).coerceAtLeast(0) else 0
        playFrom(target)
    }

    /** Restore a remembered position (e.g. after device rotation) as "paused here". */
    fun restorePosition(sentenceIndex: Int) {
        if (sentences.isEmpty()) return
        currentSentence = sentenceIndex.coerceIn(0, sentences.size - 1)
        isSpeaking = false
        isPaused = sentenceIndex > 0
    }

    fun release() {
        generation++
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /* ---------------------------- callbacks ---------------------------- */

    private fun utteranceId(gen: Int, index: Int) = "$gen:$index"

    private fun parseId(id: String?): Pair<Int, Int>? {
        val parts = id?.split(":") ?: return null
        if (parts.size != 2) return null
        val gen = parts[0].toIntOrNull() ?: return null
        val idx = parts[1].toIntOrNull() ?: return null
        return gen to idx
    }

    // Callbacks arrive on a binder thread; hop to main BEFORE touching state so
    // everything (incl. the generation check) is main-thread-confined.
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post { handleStart(utteranceId) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            mainHandler.post { handleRange(utteranceId, start, end) }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post { handleDone(utteranceId) }
        }

        @Deprecated("Deprecated in API 21")
        override fun onError(utteranceId: String?) {
            mainHandler.post { handleError(utteranceId, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { handleError(utteranceId, errorCode) }
        }
    }

    private fun handleStart(utteranceId: String?) {
        val (gen, idx) = parseId(utteranceId) ?: return
        if (gen != generation || idx >= sentences.size) return
        currentSentence = idx
        val s = sentences[idx]
        listener.onSentenceStart(idx, s.start, s.end)
    }

    private fun handleRange(utteranceId: String?, start: Int, end: Int) {
        val (gen, idx) = parseId(utteranceId) ?: return
        if (gen != generation || idx >= sentences.size) return
        val s = sentences[idx]
        val a = (s.start + start).coerceIn(0, fullText.length)
        val b = (s.start + end).coerceIn(a, fullText.length)
        if (b > a) listener.onWord(a, b)
    }

    private fun handleDone(utteranceId: String?) {
        val (gen, idx) = parseId(utteranceId) ?: return
        if (gen != generation) return
        if (idx == sentences.size - 1) {
            isSpeaking = false
            isPaused = false
            currentSentence = 0
            listener.onFinished()
        } else if (idx >= enqueuedUpTo - 5 && enqueuedUpTo < sentences.size) {
            // Approaching the end of the enqueued window — top it up.
            enqueueWindow(gen, enqueuedUpTo, flushFirst = false)
        }
    }

    private fun handleError(utteranceId: String?, errorCode: Int) {
        val (gen, _) = parseId(utteranceId) ?: return
        if (gen != generation) return
        isSpeaking = false
        listener.onError("Speech error (code $errorCode)")
    }
}
