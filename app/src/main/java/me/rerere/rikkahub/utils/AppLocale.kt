package me.rerere.rikkahub.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

private const val PREFS = "rikkahub_arabic_preferences"
private const val LANGUAGE_TAG = "language_tag"

/** App-local language choices; the empty tag follows the device language. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-CN"),
    TRADITIONAL_CHINESE("zh-TW"),
    JAPANESE("ja"),
    KOREAN("ko-KR"),
    RUSSIAN("ru"),
    ARABIC("ar");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

object AppLocale {
    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LANGUAGE_TAG, ""))

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_TAG, language.tag)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            localeManager.applicationLocales = android.os.LocaleList.forLanguageTags(language.tag)
        }
    }

    fun wrap(context: Context): Context {
        val tag = current(context).tag
        if (tag.isBlank()) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
