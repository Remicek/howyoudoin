package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Whether an invite is accepted without asking. A bridge's portal invite comes from its ghost or bot, which lives on
 * the homeserver the bridge runs next to: ours. A look-alike (`@whatsapp_123:elsewhere.org`) on another server
 * isn't a bridge of ours, so it is shown as an invite instead. With [acceptDirectInvites], people's direct-chat
 * invites are accepted too.
 */
internal fun shouldAutoJoin(
    inviterId: String?,
    ownUserId: String,
    isDirect: Boolean,
    acceptDirectInvites: Boolean
): Boolean {
    if (inviterId.isNullOrEmpty()) return false
    val fromOwnBridge = ChatNetwork.fromUserId(inviterId) != null && serverOf(inviterId) == serverOf(ownUserId)
    return fromOwnBridge || (acceptDirectInvites && isDirect)
}

/** The `is_direct` flag of our invite's membership: only a JSON `true` counts, not the string "true". */
internal fun isDirectInvite(membershipContent: JsonObject?): Boolean {
    val flag = membershipContent?.get("is_direct") as? JsonPrimitive ?: return false
    return !flag.isString && flag.booleanOrNull == true
}

/** The server name of a Matrix user ID (`@local:server`, with any port), lower-cased; "" when there is none. */
private fun serverOf(userId: String): String = userId.substringAfter(':', "").lowercase()
