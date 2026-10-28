package com.devvalor.howyoudoing.networks.presentation.tutorial

import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_back
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_don_t_show_this_guide
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_how_to_connect
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_next
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_recommended
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_skip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockCodeEntryScreen
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockLinkIntro
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockLinkedDevicesScreen
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockQrOtherScreen
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockScannerScreen
import com.devvalor.howyoudoing.networks.presentation.components.mockups.MockSettingsScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TutorialRoot(
    onFinish: (flowId: String?) -> Unit,
    onBack: () -> Unit,
    viewModel: TutorialViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is TutorialEvent.Finish -> onFinish(event.flowId)
        }
    }

    TutorialScreen(
        state = state,
        onAction = { action ->
            when (action) {
                TutorialAction.OnBackClick -> onBack()
                else -> viewModel.onAction(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    state: TutorialState,
    onAction: (TutorialAction) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.currentPage) { state.pages.size }

    // State → pager (Next/Back buttons) and pager → state (swipes)
    LaunchedEffect(state.currentPage) {
        if (pagerState.currentPage != state.currentPage) pagerState.animateScrollToPage(state.currentPage)
    }
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != state.currentPage) onAction(TutorialAction.OnPageChange(pagerState.settledPage))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(TutorialAction.OnBackClick) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.tutorial_back)
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetworkBadge(network = state.network, size = 26.dp, borderColor = Color.Transparent)
                        Spacer(Modifier.width(HowYouDoinSpacing.sm))
                        Text(stringResource(Res.string.tutorial_how_to_connect))
                    }
                },
                actions = {
                    if (!state.isLastPage) {
                        TextButton(onClick = { onAction(TutorialAction.OnSkipClick) }) {
                            Text(stringResource(Res.string.tutorial_skip))
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomControls(state = state, onAction = onAction)
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { index ->
            when (val page = state.pages[index]) {
                is TutorialPage.Info -> InfoPage(page)
                is TutorialPage.Choice -> ChoicePage(page, state, onAction)
            }
        }
    }
}

@Composable
private fun InfoPage(page: TutorialPage.Info) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HowYouDoinSpacing.containerPadding),
    ) {
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(420.dp)) {
            when (page.illustration) {
                TutorialIllustration.LINK_INTRO -> MockLinkIntro()
                TutorialIllustration.SETTINGS -> MockSettingsScreen()
                TutorialIllustration.LINKED_DEVICES -> MockLinkedDevicesScreen()
                TutorialIllustration.SCANNER -> MockScannerScreen()
                TutorialIllustration.CODE_ENTRY -> MockCodeEntryScreen()
                TutorialIllustration.QR_OTHER_SCREEN -> MockQrOtherScreen()
            }
        }
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        page.badge?.let { badge ->
            AssistChip(onClick = {}, label = { Text(stringResource(badge)) }, enabled = false)
            Spacer(Modifier.height(HowYouDoinSpacing.xs))
        }
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(
            stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
    }
}

@Composable
private fun ChoicePage(page: TutorialPage.Choice, state: TutorialState, onAction: (TutorialAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HowYouDoinSpacing.containerPadding),
    ) {
        Spacer(Modifier.height(HowYouDoinSpacing.xl))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        page.options.forEach { option ->
            Card(
                onClick = { onAction(TutorialAction.OnOptionClick(option.flowId)) },
                colors = CardDefaults.cardColors(
                    containerColor = if (option.isRecommended) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(HowYouDoinSpacing.md)) {
                    Icon(
                        if (option.flowId == "qr") Icons.Rounded.QrCode2 else Icons.Rounded.Dialpad,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(option.title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (option.isRecommended) {
                                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                                Text(
                                    stringResource(Res.string.tutorial_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(option.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
        }
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onAction(TutorialAction.OnDontShowAgainToggle) },
        ) {
            Checkbox(
                checked = state.dontShowAgain,
                onCheckedChange = { onAction(TutorialAction.OnDontShowAgainToggle) })
            Text(stringResource(Res.string.tutorial_don_t_show_this_guide), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BottomControls(state: TutorialState, onAction: (TutorialAction) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    ) {
        Box(Modifier.weight(1f)) {
            if (state.currentPage > 0) {
                TextButton(onClick = { onAction(TutorialAction.OnPreviousClick) }) {
                    Text(stringResource(Res.string.tutorial_back))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(state.pages.size) { index -> PageDot(isActive = index == state.currentPage) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (!state.isLastPage) {
                Button(onClick = { onAction(TutorialAction.OnNextClick) }) {
                    Text(stringResource(Res.string.tutorial_next))
                }
            }
        }
    }
}

@Composable
private fun PageDot(isActive: Boolean) {
    val width by animateDpAsState(if (isActive) 20.dp else 8.dp, label = "dotWidth")
    val color by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "dotColor",
    )
    Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
}
