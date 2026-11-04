package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.chat.domain.CallInfo
import com.devvalor.howyoudoing.chat.domain.CallOutcome
import com.devvalor.howyoudoing.core.presentation.util.UiText
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.call_ended
import howyoudoin.feature.chat.presentation.generated.resources.call_ended_video_duration
import howyoudoin.feature.chat.presentation.generated.resources.call_incoming_video
import howyoudoin.feature.chat.presentation.generated.resources.call_missed
import kotlin.test.Test
import kotlin.test.assertEquals

class CallDescriptionTest {

    @Test
    fun a_ringing_video_call_names_the_app_to_answer_it_in() {
        assertEquals(
            UiText.Resource(Res.string.call_incoming_video, arrayOf("WhatsApp")),
            CallInfo(CallOutcome.INCOMING, isVideo = true).describe("WhatsApp")
        )
    }

    @Test
    fun ended_calls_say_how_long_they_lasted_when_known() {
        assertEquals(
            UiText.Resource(Res.string.call_ended_video_duration, arrayOf("1:15")),
            CallInfo(CallOutcome.ENDED, isVideo = true, durationMillis = 75_000).describe("Signal")
        )
        assertEquals(UiText.Resource(Res.string.call_ended), CallInfo(CallOutcome.ENDED, isVideo = false).describe("X"))
        assertEquals(
            UiText.Resource(Res.string.call_missed),
            CallInfo(CallOutcome.MISSED, isVideo = false).describe("X")
        )
    }

    @Test
    fun durations_over_an_hour_show_hours() {
        assertEquals("0:05", formatCallDuration(5_000))
        assertEquals("1:02:05", formatCallDuration(3_725_000))
    }
}
