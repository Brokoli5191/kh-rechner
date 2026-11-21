package app.brokoli5191.khrechner.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Hands a carb amount to AndroidAPS the only way that keeps the user in charge: the number goes
 * to the clipboard and AAPS is opened, where the user pastes it into the carbs dialog and confirms.
 *
 * There is deliberately nothing more to it. Stock AAPS offers no way to pre-fill that dialog from
 * another app, and the paths that do accept carbs write them without asking.
 */
interface AapsHandoff {

    /** True if AAPS is installed and can be opened. Cheap enough to ask again on every resume. */
    fun isInstalled(): Boolean

    /**
     * Copies [grams] as plain text and opens AAPS.
     *
     * @return false if AAPS could not be opened (the amount is on the clipboard either way).
     */
    fun copyAndOpen(grams: Int): Boolean
}

class AndroidAapsHandoff(context: Context) : AapsHandoff {

    private val context = context.applicationContext

    override fun isInstalled(): Boolean = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    override fun copyAndOpen(grams: Int): Boolean {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, grams.toString()))
        // The launch intent carries FLAG_ACTIVITY_NEW_TASK, so the application context can start it.
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try {
            context.startActivity(launch)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        /**
         * applicationId of the "full" AAPS flavour, from nightscout/AndroidAPS `app/build.gradle.kts`
         * (commit f77abd9b82b308e8fc330da0d7a31370fb4a0f49, v4.0.0). Must also be listed under
         * `<queries>` in the manifest, otherwise the package is invisible to this app.
         */
        const val PACKAGE: String = "info.nightscout.androidaps"

        private const val CLIP_LABEL = "kohlenhydrate"
    }
}
