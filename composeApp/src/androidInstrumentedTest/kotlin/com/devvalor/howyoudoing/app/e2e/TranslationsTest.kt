package com.devvalor.howyoudoing.app.e2e

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The welcome screen shows in each translation the app ships (picked per app, Android 13+). */
@RunWith(Parameterized::class)
class TranslationsTest(locale: String, private val tagline: String, private val demoButton: String) {

    private val rules = AppTestRules(locale)
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Before
    fun needsPerAppLanguages() = assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    @Test
    fun welcomeScreenIsTranslated() {
        app.waitForText(tagline)
        app.onNodeWithText(demoButton).assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(
            arrayOf("en-US", "All your chats. One place.", "Try demo mode"),
            arrayOf("pl-PL", "Wszystkie czaty. Jedno miejsce.", "Wypróbuj tryb demo"),
            arrayOf("es-ES", "Todos tus chats. Un solo lugar.", "Probar el modo demo"),
            arrayOf("pt-BR", "Todas as suas conversas. Um só lugar.", "Experimentar o modo demo"),
            arrayOf("ru-RU", "Все ваши чаты. В одном месте.", "Попробовать демо-режим"),
            arrayOf("de-DE", "Alle deine Chats. Ein Ort.", "Demo-Modus testen"),
            arrayOf("fr-FR", "Toutes vos discussions. Un seul endroit.", "Essayer le mode démo"),
            arrayOf("in-ID", "Semua chat Anda. Satu tempat.", "Coba mode demo"),
            arrayOf("tr-TR", "Tüm sohbetleriniz. Tek bir yerde.", "Demo modunu dene"),
            arrayOf("ja-JP", "すべてのチャットを、ひとつの場所に。", "デモモードを試す"),
            arrayOf("hi-IN", "आपकी सारी चैट। एक ही जगह।", "डेमो मोड आज़माएँ"),
            arrayOf("ar-EG", "كل محادثاتك. في مكان واحد.", "جرّب الوضع التجريبي"),
        )
    }
}
