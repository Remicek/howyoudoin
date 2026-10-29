package com.devvalor.howyoudoing.chat.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.home_chats
import howyoudoin.feature.chat.presentation.generated.resources.home_contacts
import howyoudoin.feature.chat.presentation.generated.resources.home_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The app's top-level places, switched with the bar at the bottom of the main screens. */
enum class HomeTab(val icon: ImageVector, val label: StringResource) {
    CHATS(Icons.Rounded.ChatBubble, Res.string.home_chats),
    CONTACTS(Icons.Rounded.People, Res.string.home_contacts),
    SETTINGS(Icons.Rounded.Settings, Res.string.home_settings),
}

/** Chats, Contacts and Settings; [unread] shows on Chats while there are unread messages. */
@Composable
fun HomeBottomBar(selected: HomeTab, onSelect: (HomeTab) -> Unit, unread: Int = 0) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (tab == HomeTab.CHATS && unread > 0) Badge() }) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
