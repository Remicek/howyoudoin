package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.chat.domain.CallInfo
import com.devvalor.howyoudoing.chat.domain.CallOutcome
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which call messages offer "Call back here" (a call through a link). */
class CallBackTest {

    private fun call(state: CallOutcome, video: Boolean = false, outgoing: Boolean = false) = Message(
        id = "m", chatId = "c", senderId = "s", senderName = "Anna", body = "", timestamp = 0,
        isOutgoing = outgoing, type = MessageType.CALL, call = CallInfo(state, isVideo = video),
    )

    private fun callBackOf(message: Message, network: ChatNetwork = ChatNetwork.WHATSAPP) =
        listOf(message).toMessageUis(showSenderNames = false, network = network).single().callBackVideo

    @Test
    fun their_ringing_missed_or_declined_calls_can_be_returned_the_same_way() {
        assertEquals(true, callBackOf(call(CallOutcome.INCOMING, video = true)))
        assertEquals(false, callBackOf(call(CallOutcome.MISSED)))
        assertEquals(false, callBackOf(call(CallOutcome.DECLINED)))
    }

    @Test
    fun calls_that_were_talked_through_or_ours_are_not() {
        assertEquals(null, callBackOf(call(CallOutcome.ENDED)))
        assertEquals(null, callBackOf(call(CallOutcome.STARTED)))
        assertEquals(null, callBackOf(call(CallOutcome.MISSED, outgoing = true)))
    }

    @Test
    fun matrix_calls_are_answered_in_the_app_itself() {
        assertEquals(null, callBackOf(call(CallOutcome.MISSED), network = ChatNetwork.MATRIX))
    }
}
