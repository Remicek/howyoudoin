package com.relay.chat.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.relay.chat.presentation.conversation.ConversationRoot
import com.relay.chat.presentation.inbox.InboxRoot

fun NavGraphBuilder.chatGraph(
    navController: NavController,
    onNetworksClick: () -> Unit,
) {
    navigation<ChatRoutes.Graph>(startDestination = ChatRoutes.Inbox) {
        composable<ChatRoutes.Inbox> {
            InboxRoot(
                onChatClick = { chatId ->
                    navController.navigate(ChatRoutes.Conversation(chatId)) { launchSingleTop = true }
                },
                onNetworksClick = onNetworksClick,
            )
        }
        composable<ChatRoutes.Conversation> {
            ConversationRoot(onBack = { navController.navigateUp() })
        }
    }
}
