package com.devvalor.howyoudoing.chat.presentation.navigation

import com.devvalor.howyoudoing.chat.presentation.contacts.ContactsRoot
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.devvalor.howyoudoing.chat.presentation.conversation.ConversationRoot
import com.devvalor.howyoudoing.chat.presentation.inbox.InboxRoot

@Suppress("LongParameterList") // one callback per screen it leads out to
fun NavGraphBuilder.chatGraph(
    navController: NavController,
    /** Opens Networks; with a network, how to disconnect and connect it again (reconnecting didn't help). */
    onNetworksClick: (troubleshoot: ChatNetwork?) -> Unit,
    onSettingsClick: () -> Unit,
    onGuideClick: () -> Unit = {},
    onPremiumClick: () -> Unit = {},
    inboxBanner: @Composable (encryptedNetworks: Set<ChatNetwork>) -> Unit = {},
) {
    navigation<ChatRoutes.Graph>(startDestination = ChatRoutes.Inbox) {
        composable<ChatRoutes.Inbox> {
            InboxRoot(
                onChatClick = { chatId ->
                    navController.navigate(ChatRoutes.Conversation(chatId)) { launchSingleTop = true }
                },
                onNetworksClick = { onNetworksClick(null) },
                onSettingsClick = onSettingsClick,
                onContactsClick = { navController.navigate(ChatRoutes.Contacts) { launchSingleTop = true } },
                onNewMessageClick = { navController.navigate(ChatRoutes.NewMessage) { launchSingleTop = true } },
                onGuideClick = onGuideClick,
                onFixConnectionClick = { network -> onNetworksClick(network) },
                banner = inboxBanner,
            )
        }
        composable<ChatRoutes.Contacts> {
            ContactsRoot(
                isNewMessage = false,
                onChatOpen = { chatId -> navController.navigate(ChatRoutes.Conversation(chatId)) },
                onClose = { navController.navigateUp() },
                onChatsTab = { navController.popBackStack(ChatRoutes.Inbox, inclusive = false) },
                onSettingsTab = onSettingsClick,
            )
        }
        composable<ChatRoutes.NewMessage> {
            ContactsRoot(
                isNewMessage = true,
                // The chat replaces the new message screen, so back from it goes to the inbox
                onChatOpen = { chatId ->
                    navController.navigate(ChatRoutes.Conversation(chatId)) {
                        popUpTo(ChatRoutes.NewMessage) { inclusive = true }
                    }
                },
                onClose = { navController.navigateUp() },
            )
        }
        composable<ChatRoutes.Conversation> {
            ConversationRoot(onBack = { navController.navigateUp() }, onPremiumClick = onPremiumClick)
        }
    }
}
