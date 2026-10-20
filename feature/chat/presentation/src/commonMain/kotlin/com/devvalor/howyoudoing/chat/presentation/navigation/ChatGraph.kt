package com.devvalor.howyoudoing.chat.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.devvalor.howyoudoing.chat.presentation.conversation.ConversationRoot
import com.devvalor.howyoudoing.chat.presentation.inbox.InboxRoot

fun NavGraphBuilder.chatGraph(
    navController: NavController,
    onNetworksClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onGuideClick: () -> Unit = {},
    inboxBanner: @Composable (encryptedNetworks: Set<ChatNetwork>) -> Unit = {},
) {
    navigation<ChatRoutes.Graph>(startDestination = ChatRoutes.Inbox) {
        composable<ChatRoutes.Inbox> {
            InboxRoot(
                onChatClick = { chatId ->
                    navController.navigate(ChatRoutes.Conversation(chatId)) { launchSingleTop = true }
                },
                onNetworksClick = onNetworksClick,
                onSettingsClick = onSettingsClick,
                onGuideClick = onGuideClick,
                banner = inboxBanner,
            )
        }
        composable<ChatRoutes.Conversation> {
            ConversationRoot(onBack = { navController.navigateUp() })
        }
    }
}
