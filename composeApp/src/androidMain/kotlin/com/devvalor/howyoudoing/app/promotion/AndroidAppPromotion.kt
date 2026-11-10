package com.devvalor.howyoudoing.app.promotion

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.devvalor.howyoudoing.core.domain.app.AppPromotion

/** Google Play's page for rating, and the system share sheet for telling friends. */
class AndroidAppPromotion(private val context: Context) : AppPromotion {

    override val isSupported = true

    override fun openStorePage() {
        // The Play Store app if it's there, otherwise its web page
        try {
            context.startActivity(view("market://details?id=$PACKAGE"))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(view(AppPromotion.PLAY_STORE_URL))
        }
    }

    override fun share(message: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "HowYouDoin")
            .putExtra(Intent.EXTRA_TEXT, message)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        /** The released app's ID, also for debug builds. */
        const val PACKAGE = "com.devvalor.howyoudoing"
    }
}
