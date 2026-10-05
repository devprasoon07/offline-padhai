package com.devprasoon.offlinepadhai

import android.content.Context

/**
 * Jawab kis bhasha me aayega — user khud chunega.
 * displayName spinner me dikhega, promptName model ko diye gaye
 * instruction me jayega.
 */
data class AppLanguage(
    val code: String,
    val displayName: String,
    val promptName: String
)

object Languages {
    val ALL = listOf(
        AppLanguage("english", "English", "simple English"),
        AppLanguage("hinglish", "Hinglish", "Hinglish (Roman script me likhi Hindi + aasaan English)"),
        AppLanguage("hindi", "हिन्दी", "Hindi (Devanagari script me)"),
        AppLanguage("bengali", "বাংলা", "Bengali (Bengali script me)"),
        AppLanguage("tamil", "தமிழ்", "Tamil (Tamil script me)"),
        AppLanguage("telugu", "తెలుగు", "Telugu (Telugu script me)"),
        AppLanguage("marathi", "मराठी", "Marathi (Devanagari script me)"),
        AppLanguage("gujarati", "ગુજરાતી", "Gujarati (Gujarati script me)"),
        AppLanguage("kannada", "ಕನ್ನಡ", "Kannada (Kannada script me)"),
        AppLanguage("malayalam", "മലയാളം", "Malayalam (Malayalam script me)"),
        AppLanguage("punjabi", "ਪੰਜਾਬੀ", "Punjabi (Gurmukhi script me)"),
        AppLanguage("urdu", "اردو", "Urdu (Urdu script me)")
    )

    fun byCode(code: String?): AppLanguage =
        ALL.find { it.code == code } ?: ALL[0]
}

object AppPrefs {
    private const val PREFS = "padhai_prefs"
    private const val KEY_LANG = "answer_language"

    fun getLanguage(context: Context): AppLanguage {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "english")
        return Languages.byCode(code)
    }

    fun setLanguage(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, code).apply()
    }
}
