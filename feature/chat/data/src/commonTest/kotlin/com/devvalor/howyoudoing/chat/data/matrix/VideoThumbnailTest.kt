package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.mappers.toMessages
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoThumbnailTest {

    private fun video(info: JsonObject, encrypted: Boolean) = MatrixEventDto(
        type = "m.room.message",
        eventId = "\$1",
        sender = "@other:x",
        originServerTs = 1L,
        content = buildJsonObject {
            put("msgtype", JsonPrimitive("m.video"))
            put("body", JsonPrimitive("clip.mp4"))
            if (encrypted) {
                put("file", buildJsonObject { put("url", JsonPrimitive("mxc://x/video")) })
            } else {
                put("url", JsonPrimitive("mxc://x/video"))
            }
            put("info", info)
        }
    )

    private fun thumbnailOf(event: MatrixEventDto) =
        RoomCache("!r:x", timeline = listOf(event)).toMessages("@me:x").single().media?.thumbnailUrl

    @Test
    fun unencryptedVideoUsesThumbnailUrl() {
        val info = buildJsonObject { put("thumbnail_url", JsonPrimitive("mxc://x/thumb")) }
        assertEquals("mxc://x/thumb", thumbnailOf(video(info, encrypted = false)))
    }

    @Test
    fun encryptedVideoUsesThumbnailFile() {
        // As Element sends it in an encrypted room
        val info = buildJsonObject {
            put("thumbnail_file", buildJsonObject { put("url", JsonPrimitive("mxc://x/thumb")) })
        }
        assertEquals("mxc://x/thumb", thumbnailOf(video(info, encrypted = true)))
    }
}
