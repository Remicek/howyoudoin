package com.devvalor.howyoudoing.networks.presentation.connect

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.core.presentation.util.asUiText
import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.flow_messenger_via_server_bridge
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginFlowLabelsTest {

    private fun flow(id: String) = BridgeLoginFlow(id, "bridge name $id", "bridge description $id")

    @Test
    fun messengerFlowsAreRelabelledAndRecommendedFirst() {
        val bridgeOrder = listOf("facebook", "messenger", "messenger-lite", "messenger-lite-android", "howyoudoin-on-device").map(::flow)
        val labels = LoginFlowLabels.present(ChatNetwork.MESSENGER, bridgeOrder)
        assertEquals(
            listOf("howyoudoin-on-device", "messenger", "facebook", "messenger-lite", "messenger-lite-android"),
            labels.map { it.flowId }
        )
        assertEquals("Messenger".asUiText(), labels.first().title)
        assertEquals(true, labels.first().isRecommended)
        assertEquals(UiText.Resource(Res.string.flow_messenger_via_server_bridge), labels[1].title)
    }

    @Test
    fun unknownFlowsKeepBridgeTextAndGoLast() {
        val labels = LoginFlowLabels.present(ChatNetwork.MESSENGER, listOf(flow("new-flow"), flow("messenger")))
        assertEquals(listOf("messenger", "new-flow"), labels.map { it.flowId })
        assertEquals("bridge name new-flow".asUiText(), labels.last().title)
    }
}
