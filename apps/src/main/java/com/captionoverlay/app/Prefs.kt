package com.captionoverlay.app

import android.content.Context

/**
 * Thin wrapper around SharedPreferences for the handful of settings the
 * overlay needs to remember between launches.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("caption_overlay_prefs", Context.MODE_PRIVATE)

    // BCP-47 language tags supported out of the box. Feel free to extend.
    val supportedLanguages = listOf(
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "es-ES" to "Spanish",
        "fr-FR" to "French",
        "de-DE" to "German",
        "hi-IN" to "Hindi",
        "ja-JP" to "Japanese",
        "pt-BR" to "Portuguese (BR)"
    )

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "en-US") ?: "en-US"
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    // Index into the FONT_SIZES list below.
    var fontSizeIndex: Int
        get() = sp.getInt(KEY_FONT_SIZE, 1)
        set(value) = sp.edit().putInt(KEY_FONT_SIZE, value).apply()

    // Index into the OPACITIES list below.
    var opacityIndex: Int
        get() = sp.getInt(KEY_OPACITY, 1)
        set(value) = sp.edit().putInt(KEY_OPACITY, value).apply()

    var micEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_MIC_ENABLED, value).apply()

    fun cycleLanguage(): String {
        val codes = supportedLanguages.map { it.first }
        val next = (codes.indexOf(language) + 1) % codes.size
        language = codes[next]
        return language
    }

    fun cycleFontSize(): Float {
        fontSizeIndex = (fontSizeIndex + 1) % FONT_SIZES.size
        return FONT_SIZES[fontSizeIndex]
    }

    fun currentFontSizeSp(): Float = FONT_SIZES[fontSizeIndex]

    fun cycleOpacity(): Float {
        opacityIndex = (opacityIndex + 1) % OPACITIES.size
        return OPACITIES[opacityIndex]
    }

    fun currentOpacity(): Float = OPACITIES[opacityIndex]

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_FONT_SIZE = "font_size_index"
        private const val KEY_OPACITY = "opacity_index"
        private const val KEY_MIC_ENABLED = "mic_enabled"

        val FONT_SIZES = floatArrayOf(14f, 18f, 24f, 32f)
        val OPACITIES = floatArrayOf(0.5f, 0.85f, 1.0f)
    }
}
