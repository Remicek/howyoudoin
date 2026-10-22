package com.devvalor.howyoudoing.app.backup

import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedLoginsTest {

    private val matrix = AuthInfo("https://matrix.example.org", "@me:example.org", "token", "DEVICE")

    private val saved = SavedLogins(
        savedAtMillis = 1_700_000_000_000,
        enabled = true,
        appSession = AuthInfo.localOnly(),
        cookieLogins = mapOf(
            ChatNetwork.MESSENGER to """{"xs":"1","c_user":"2","datr":"3"}""",
            ChatNetwork.SLACK to """{"token":"xoxc-1","d":"xoxd-2"}"""
        ),
        matrixAccount = matrix,
        telegram = TelegramFiles(session = "{\"session\":1}", state = "{\"self\":2}"),
        relink = listOf(ChatNetwork.WHATSAPP, ChatNetwork.SIGNAL)
    )

    private val nothingHere = LoginsHere(emptySet(), hasTelegram = false, hasMatrixAccount = false, hasAppSession = false, linked = emptySet())

    @Test
    fun theFileReadsBackWhatWasSaved() {
        val read = assertNotNull(SavedLoginsFormat.decode(SavedLoginsFormat.encode(saved)))
        assertEquals(saved, read)
        assertEquals(mapOf("token" to "xoxc-1", "d" to "xoxd-2"), read.cookies(ChatNetwork.SLACK))
    }

    @Test
    fun aDemoSessionIsNeverSaved() {
        val read = assertNotNull(SavedLoginsFormat.decode(SavedLoginsFormat.encode(saved.copy(appSession = AuthInfo.demo()))))
        assertNull(read.appSession)
    }

    @Test
    fun brokenFilesAreIgnored() {
        assertNull(SavedLoginsFormat.decode("not json"))
        assertNull(SavedLoginsFormat.decode("[1, 2]"))
    }

    @Test
    fun aNewPhoneIsOfferedEverythingSaved() {
        val offer = assertNotNull(saved.offerFor(nothingHere))
        assertEquals(
            listOf(ChatNetwork.MESSENGER, ChatNetwork.TELEGRAM, ChatNetwork.SLACK, ChatNetwork.MATRIX),
            offer.restorable
        )
        assertTrue(offer.includesAppSession)
        assertEquals(listOf(ChatNetwork.WHATSAPP, ChatNetwork.SIGNAL), offer.toRelink)
        assertEquals(saved.savedAtMillis, offer.savedAtMillis)
    }

    @Test
    fun whatIsAlreadyConnectedHereIsNotOffered() {
        val here = LoginsHere(
            cookieLogins = setOf(ChatNetwork.MESSENGER),
            hasTelegram = true,
            hasMatrixAccount = false,
            hasAppSession = true,
            linked = setOf(ChatNetwork.WHATSAPP)
        )
        val offer = assertNotNull(saved.offerFor(here))
        assertEquals(listOf(ChatNetwork.SLACK, ChatNetwork.MATRIX), offer.restorable)
        assertFalse(offer.includesAppSession)
        assertEquals(listOf(ChatNetwork.SIGNAL), offer.toRelink)
    }

    @Test
    fun thePhoneTheBackupCameFromIsOfferedNothing() {
        val here = LoginsHere(
            cookieLogins = setOf(ChatNetwork.MESSENGER, ChatNetwork.SLACK),
            hasTelegram = true,
            hasMatrixAccount = true,
            hasAppSession = true,
            linked = setOf(ChatNetwork.WHATSAPP, ChatNetwork.SIGNAL)
        )
        assertNull(saved.offerFor(here))
    }

    @Test
    fun backupIsOfferedOnceAfterTwoNetworks() {
        assertFalse(shouldOfferBackup(alreadyOffered = false, restoreDecided = true, connectedNetworks = 1))
        assertTrue(shouldOfferBackup(alreadyOffered = false, restoreDecided = true, connectedNetworks = 2))
        assertFalse(shouldOfferBackup(alreadyOffered = true, restoreDecided = true, connectedNetworks = 3))
        // Not while a restore is still waiting for an answer
        assertFalse(shouldOfferBackup(alreadyOffered = false, restoreDecided = false, connectedNetworks = 3))
    }
}
