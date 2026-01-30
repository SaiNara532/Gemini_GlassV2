package com.impairedVision.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class SpeechHelper(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastSpokenText: String? = null
    private var lastSpokenTime: Long = 0

    private val duplicateCooldownMs = 3000L

    init {

        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            } else {
                isReady = true
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    /**
     * Speaks the text if it hasn't been spoken recently.
     * @param text The text to read aloud.
     * @param force If true, it interrupts the current speech to say this immediately.
     */
    fun speak(text: String, force: Boolean = false) {
        if (!isReady) return

        val currentTime = System.currentTimeMillis()

        if (force || text != lastSpokenText || (currentTime - lastSpokenTime) > duplicateCooldownMs) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)

            lastSpokenText = text
            lastSpokenTime = currentTime
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}