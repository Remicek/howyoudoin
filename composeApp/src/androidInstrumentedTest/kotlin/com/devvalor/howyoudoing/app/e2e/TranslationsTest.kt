package com.devvalor.howyoudoing.app.e2e

import android.os.Build
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
        app.onNodeWithText(demoButton).performScrollTo()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales() = listOf(
            arrayOf("en-US", "All your chats in one inbox", "Try demo mode"),
            arrayOf("pl-PL", "Wszystkie czaty w jednej skrzynce", "Wypróbuj tryb demo"),
            arrayOf("es-ES", "Todos tus chats en una sola bandeja", "Probar el modo demo"),
            arrayOf("pt-BR", "Todas as suas conversas em uma só caixa de entrada", "Experimentar o modo demo"),
            arrayOf("ru-RU", "Все чаты в одном месте", "Попробовать демо-режим"),
            arrayOf("de-DE", "Alle Chats in einem Posteingang", "Demo-Modus testen"),
            arrayOf("fr-FR", "Toutes vos discussions dans une seule boîte", "Essayer le mode démo"),
            arrayOf("in-ID", "Semua chat Anda dalam satu kotak masuk", "Coba mode demo"),
            arrayOf("tr-TR", "Tüm sohbetleriniz tek bir gelen kutusunda", "Demo modunu dene"),
            arrayOf("ja-JP", "すべてのチャットをひとつの受信箱に", "デモモードを試す"),
            arrayOf("hi-IN", "आपकी सारी चैट एक ही इनबॉक्स में", "डेमो मोड आज़माएँ"),
            arrayOf("ar-EG", "كل محادثاتك في صندوق واحد", "جرّب الوضع التجريبي"),
        )
    }
}
