package com.devvalor.howyoudoing.core.domain.app

/** Rating the app in its store and telling friends about it, from Settings. */
interface AppPromotion {
    /** False where the app isn't in a store we can open (iOS isn't released). */
    val isSupported: Boolean

    /** Opens the app's store page, where it can be rated. */
    fun openStorePage()

    /** Opens the system share sheet with [message] (the receiving app lets the user edit it before sending). */
    fun share(message: String)

    companion object {
        /** The app's page on Google Play; release and debug builds both point at the released app. */
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.devvalor.howyoudoing"
    }
}

/** Where there's no store to open. */
object NoAppPromotion : AppPromotion {
    override val isSupported = false
    override fun openStorePage() = Unit
    override fun share(message: String) = Unit
}
