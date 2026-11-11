package com.devvalor.howyoudoing.settings.presentation.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.app.HiddenTips
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.settings_tips
import howyoudoin.feature.settings.presentation.generated.resources.settings_tips_sub
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Brings back the inbox tips turned off with "Don't show again". */
class HiddenTipsViewModel(private val hiddenTips: HiddenTips) : ViewModel() {
    val hasHidden = hiddenTips.observeAnyHidden()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun showAll() {
        viewModelScope.launch { hiddenTips.showAll() }
    }
}

/** Shown only while a tip is turned off; tapping it brings them all back and the row goes away. */
@Composable
fun HiddenTipsRow(viewModel: HiddenTipsViewModel = koinViewModel()) {
    val hasHidden by viewModel.hasHidden.collectAsStateWithLifecycle()
    if (!hasHidden) return
    SettingsRow(
        title = stringResource(Res.string.settings_tips),
        subtitle = stringResource(Res.string.settings_tips_sub),
        icon = Icons.Rounded.Lightbulb,
        onClick = viewModel::showAll,
    )
}
