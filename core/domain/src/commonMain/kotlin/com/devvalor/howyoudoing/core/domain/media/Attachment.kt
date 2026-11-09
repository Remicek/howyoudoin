package com.devvalor.howyoudoing.core.domain.media

/** A file picked by the user, or a voice message they recorded, ready to upload. */
@Suppress("LongParameterList") // A file and what networks need to show it
class Attachment(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    /** Set for a recorded voice message, which networks show as a voice note rather than an audio file. */
    val voice: VoiceNote? = null,
    /** A video's length, when known. */
    val durationMillis: Long? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVoice: Boolean get() = voice != null
}

/** A recorded voice message's length and its loudness over the recording, 0–255 per sample, for the waveform. */
data class VoiceNote(val durationMillis: Long, val waveform: List<Int>? = null)

/** A message being replied to or reacted to: its ID, who sent it and its text (some networks quote it). */
data class MessageRef(val id: String, val senderId: String = "", val text: String = "")
