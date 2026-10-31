package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem.Kind
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.core.presentation.util.asUiText
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.problem_expired_text
import howyoudoin.feature.chat.presentation.generated.resources.problem_expired_title
import howyoudoin.feature.chat.presentation.generated.resources.problem_failed_text
import howyoudoin.feature.chat.presentation.generated.resources.problem_failed_title
import howyoudoin.feature.chat.presentation.generated.resources.problem_logged_out_text
import howyoudoin.feature.chat.presentation.generated.resources.problem_logged_out_title
import howyoudoin.feature.chat.presentation.generated.resources.problem_reconnecting_text
import howyoudoin.feature.chat.presentation.generated.resources.problem_reconnecting_title
import howyoudoin.feature.chat.presentation.generated.resources.problems_text
import howyoudoin.feature.chat.presentation.generated.resources.problems_title

/** What the inbox banner and the notification say about a network that stopped working. */
val ConnectionProblem.title: UiText
    get() = UiText.Resource(
        when (kind) {
            Kind.FAILED -> Res.string.problem_failed_title
            Kind.LOGGED_OUT -> Res.string.problem_logged_out_title
            Kind.RECONNECTING -> Res.string.problem_reconnecting_title
            Kind.LOGIN_EXPIRED -> Res.string.problem_expired_title
        },
        arrayOf(network.displayName)
    )

val ConnectionProblem.text: UiText
    get() = when (kind) {
        // The network's own reason, when it gave one
        Kind.FAILED -> detail?.takeIf { it.isNotBlank() }?.asUiText() ?: UiText.Resource(Res.string.problem_failed_text)
        Kind.LOGGED_OUT -> UiText.Resource(Res.string.problem_logged_out_text)
        Kind.RECONNECTING -> UiText.Resource(Res.string.problem_reconnecting_text)
        Kind.LOGIN_EXPIRED -> UiText.Resource(Res.string.problem_expired_text)
    }

/** Several networks at once are named together in one banner; one network keeps its own title and reason. */
val List<ConnectionProblem>.title: UiText
    get() = singleOrNull()?.title
        ?: UiText.Resource(Res.string.problems_title, arrayOf(joinToString { it.network.displayName }))

val List<ConnectionProblem>.text: UiText
    get() = singleOrNull()?.text ?: UiText.Resource(Res.string.problems_text)

/** Problems the user can try to fix right here by reconnecting. */
val List<ConnectionProblem>.canReconnect: Boolean
    get() = any { it.kind == Kind.RECONNECTING || it.kind == Kind.FAILED }
