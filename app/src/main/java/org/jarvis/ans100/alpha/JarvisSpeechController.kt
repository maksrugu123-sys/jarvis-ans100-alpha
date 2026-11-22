package org.jarvis.ans100.alpha

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Lightweight Android system-TTS output for JARVIS.
 *
 * Uses an installed Russian voice when available. Pitch and rate are tuned for
 * a calm, measured assistant delivery; the actual voice timbre depends on the
 * device's installed TTS engine and voice packs.
 */
class JarvisSpeechController(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    @Volatile private var ready = false
    @Volatile private var languageAvailable = false
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        val russian = Locale("ru", "RU")
        val languageResult = tts.setLanguage(russian)
        languageAvailable = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!languageAvailable) {
            ready = false
            return
        }

        // Prefer a high-quality local Russian voice to avoid network speech.
        val localRussianVoice = runCatching {
            tts.voices
                ?.filter { it.locale.language.equals("ru", ignoreCase = true) }
                ?.filterNot { it.isNetworkConnectionRequired }
                ?.maxWithOrNull(
                    compareBy<Voice> { it.quality }
                        .thenByDescending { -it.latency },
                )
        }.getOrNull()
        if (localRussianVoice != null) {
            runCatching { tts.voice = localRussianVoice }
        }
        tts.setPitch(JARVIS_PITCH)
        tts.setSpeechRate(JARVIS_SPEECH_RATE)
        ready = true
    }

    fun speak(text: String): Boolean {
        val clean = text.trim().take(MAX_SPEECH_CHARS)
        if (clean.isEmpty()) return false
        val tts = engine ?: return false
        if (!ready || !languageAvailable) return false
        tts.stop()
        return tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}") ==
            TextToSpeech.SUCCESS
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    companion object {
        private const val MAX_SPEECH_CHARS = 4_000
        private const val JARVIS_PITCH = 0.86f
        private const val JARVIS_SPEECH_RATE = 0.92f
    }
}
