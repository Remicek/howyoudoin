package com.devvalor.howyoudoing.core.presentation.util

import com.devvalor.howyoudoing.core.domain.util.DataError
import howyoudoin.core.presentation.generated.resources.Res
import howyoudoin.core.presentation.generated.resources.error_bad_request
import howyoudoin.core.presentation.generated.resources.error_cancelled
import howyoudoin.core.presentation.generated.resources.error_conflict
import howyoudoin.core.presentation.generated.resources.error_disk_full
import howyoudoin.core.presentation.generated.resources.error_forbidden
import howyoudoin.core.presentation.generated.resources.error_no_internet
import howyoudoin.core.presentation.generated.resources.error_not_found
import howyoudoin.core.presentation.generated.resources.error_payload_too_large
import howyoudoin.core.presentation.generated.resources.error_payment_required
import howyoudoin.core.presentation.generated.resources.error_request_timeout
import howyoudoin.core.presentation.generated.resources.error_serialization
import howyoudoin.core.presentation.generated.resources.error_server
import howyoudoin.core.presentation.generated.resources.error_service_unavailable
import howyoudoin.core.presentation.generated.resources.error_too_many_requests
import howyoudoin.core.presentation.generated.resources.error_unauthorized
import howyoudoin.core.presentation.generated.resources.error_unknown

fun DataError.toUiText(): UiText {
    val resource = when (this) {
        DataError.Local.DISK_FULL -> Res.string.error_disk_full
        DataError.Local.NOT_FOUND -> Res.string.error_not_found
        DataError.Local.UNKNOWN -> Res.string.error_unknown
        DataError.Remote.BAD_REQUEST -> Res.string.error_bad_request
        DataError.Remote.REQUEST_TIMEOUT -> Res.string.error_request_timeout
        DataError.Remote.UNAUTHORIZED -> Res.string.error_unauthorized
        DataError.Remote.FORBIDDEN -> Res.string.error_forbidden
        DataError.Remote.NOT_FOUND -> Res.string.error_not_found
        DataError.Remote.CONFLICT -> Res.string.error_conflict
        DataError.Remote.TOO_MANY_REQUESTS -> Res.string.error_too_many_requests
        DataError.Remote.NO_INTERNET -> Res.string.error_no_internet
        DataError.Remote.PAYLOAD_TOO_LARGE -> Res.string.error_payload_too_large
        DataError.Remote.SERVER_ERROR -> Res.string.error_server
        DataError.Remote.SERVICE_UNAVAILABLE -> Res.string.error_service_unavailable
        DataError.Remote.SERIALIZATION -> Res.string.error_serialization
        DataError.Remote.PAYMENT_REQUIRED -> Res.string.error_payment_required
        DataError.Remote.CANCELLED -> Res.string.error_cancelled
        DataError.Remote.UNKNOWN -> Res.string.error_unknown
    }
    return UiText.Resource(resource)
}
