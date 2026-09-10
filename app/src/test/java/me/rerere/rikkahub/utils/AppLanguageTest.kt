package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `all eight supported languages are present in AppLanguage enum`() {
        val expectedLanguages = listOf(
            AppLanguage.SYSTEM,
            AppLanguage.ENGLISH,
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.JAPANESE,
            AppLanguage.KOREAN,
            AppLanguage.RUSSIAN,
            AppLanguage.ARABIC,
        )
        assertEquals(expectedLanguages, AppLanguage.entries)
    }

    @Test
    fun `language tags match standard BCP-47 locale identifiers`() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.tag)
        assertEquals("zh-TW", AppLanguage.TRADITIONAL_CHINESE.tag)
        assertEquals("ja", AppLanguage.JAPANESE.tag)
        assertEquals("ko-KR", AppLanguage.KOREAN.tag)
        assertEquals("ru", AppLanguage.RUSSIAN.tag)
        assertEquals("ar", AppLanguage.ARABIC.tag)
    }

    @Test
    fun `fromTag correctly resolves each language and falls back to SYSTEM`() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromTag("ar"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromTag("ko-KR"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru"))

        // Fallbacks
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("invalid-tag"))
    }

    @Test
    fun `arabic locale is RTL`() {
        val arabicLocale = Locale.forLanguageTag(AppLanguage.ARABIC.tag)
        assertEquals("ar", arabicLocale.language)

        // Arabic character directionality check
        val arabicChar = 'م'
        val dir = Character.getDirectionality(arabicChar.code)
        assertTrue(
            "Arabic character must have RIGHT_TO_LEFT or RIGHT_TO_LEFT_ARABIC directionality",
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        )
    }
}
