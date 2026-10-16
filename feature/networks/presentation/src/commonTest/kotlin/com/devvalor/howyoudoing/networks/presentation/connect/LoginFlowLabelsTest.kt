package com.devvalor.howyoudoing.networks.presentation.connect

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginFlowLabelsTest {

    private fun flow(id: String) = BridgeLoginFlow(id, "bridge name $id", "bridge description $id")

    @Test
    fun messengerFlowsAreRelabelledAndRecommendedFirst() {
        val bridgeOrder = listOf("facebook", "messenger", "messenger-lite", "messenger-lite-android").map(::flow)
        val labels = LoginFlowLabels.present(ChatNetwork.MESSENGER, bridgeOrder)
        assertEquals(listOf("messenger", "facebook", "messenger-lite", "messenger-lite-android"), labels.map { it.flowId })
        assertEquals("Messenger", labels.first().title)
        assertEquals(true, labels.first().isRecommended)
    }

    @Test
    fun unknownFlowsKeepBridgeTextAndGoLast() {
        val labels = LoginFlowLabels.present(ChatNetwork.MESSENGER, listOf(flow("new-flow"), flow("messenger")))
        assertEquals(listOf("messenger", "new-flow"), labels.map { it.flowId })
        assertEquals("bridge name new-flow", labels.last().title)
    }
}
