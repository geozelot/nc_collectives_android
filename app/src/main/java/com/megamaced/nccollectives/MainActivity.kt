package com.megamaced.nccollectives

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.data.prefs.UserPrefs
import com.megamaced.nccollectives.share.SharePayload
import com.megamaced.nccollectives.share.SharePayloadHolder
import com.megamaced.nccollectives.ui.navigation.NcCollectivesScaffold
import com.megamaced.nccollectives.ui.theme.NcCollectivesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sharePayloadHolder: SharePayloadHolder

    @Inject
    lateinit var userPreferences: UserPreferences

    /**
     * Theme T1: whether the first [UserPrefs] has been read. The splash
     * screen stays up until it has.
     */
    @Volatile
    private var prefsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme T1: hold the splash until the preferences are in. The first
        // frame used to draw with defaults (`initialValue = UserPrefs()`) and
        // flip once DataStore answered, on every launch and every
        // recreation. A user on Light with a dark system saw dark, then
        // light, then dark again on rotation, which reads as the setting not
        // sticking. The read is one small file, so the hold is short.
        installSplashScreen().setKeepOnScreenCondition { !prefsLoaded }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // S9: drop touches that arrive through another app's window drawn
        // over this one (tapjacking). Android 12 does this itself for
        // untrusted overlays; Android 10 and 11 don't. Dialogs are their own
        // windows and guard themselves (`RefuseObscuredTouches`).
        window.decorView.filterTouchesWhenObscured = true
        // B-74: only a fresh start may publish. This activity is
        // `singleTask`, so a share intent stays its current intent
        // indefinitely — an unconditional publish here re-published a payload
        // the user had already saved on every recreation (rotation, theme
        // change, the system reclaiming the process), the navigation effect
        // dragged them back into the capture screen with content that was
        // already on the server, and tapping Create made a duplicate page.
        // A non-null `savedInstanceState` is exactly "this is a recreation,
        // not a new share".
        if (savedInstanceState == null) {
            publishShareIfPresent(intent)
        } else {
            restoreUnfinishedShare(savedInstanceState)
        }
        setContent {
            val prefs = userPreferences.flow
                .collectAsStateWithLifecycle(initialValue = null)
                .value
            if (prefs != null) {
                SideEffect { prefsLoaded = true }
                // S9: applied whenever the setting changes, including at launch.
                LaunchedEffect(prefs.hideInRecents) { applyHideInRecents(prefs.hideInRecents) }
                NcCollectivesTheme(
                    themeMode = prefs.themeMode,
                    textScale = prefs.textScale,
                    dynamicColor = prefs.dynamicColor,
                ) {
                    NcCollectivesScaffold()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishShareIfPresent(intent)
    }

    /**
     * Carry an *unfinished* share across process death — issue #42.
     *
     * [SharePayloadHolder] is process-local, and B-74's `savedInstanceState`
     * gate turned that into data loss rather than merely a cold start: when
     * Android reclaims the background process while capture, destination
     * selection, or the login browser is foreground, the task is restored
     * with a non-null saved state, so nothing republishes — and the marker
     * extra that would have permitted it does not survive either, because
     * `putExtra` mutates this process's copy of the intent while the system
     * re-delivers its own. The user's text and images were still sitting in
     * the restored intent, unreachable.
     *
     * What goes into the bundle is the payload the holder is *still* holding,
     * which is what keeps B-74 fixed: `ShareCaptureViewModel` calls
     * `consume` once the content is on the server, so a finished share
     * leaves nothing to write and nothing to replay. A share still in the
     * holder is by definition one the user has not finished with.
     *
     * The payload's own id is preserved rather than re-derived from the
     * intent, so `consume(payloadId)` and the scaffold's identity keying
     * still see one share rather than two.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sharePayloadHolder.payload.value?.writeTo(outState)
    }

    private fun restoreUnfinishedShare(savedInstanceState: Bundle) {
        // Non-empty in the same process (rotation, theme change), where the
        // singleton outlived the activity and the payload never went
        // anywhere. Only a genuine process restart leaves it empty.
        if (sharePayloadHolder.payload.value != null) return
        // The task still holds the URI grants that came with the original
        // intent, so the images stay readable; a grant that has gone is
        // handled downstream by the staging copy, which drops what it cannot
        // read and says so (issue #31).
        SharePayload.fromSavedState(savedInstanceState)?.foreignOnly(packageName)?.let(sharePayloadHolder::publish)
    }

    /**
     * Publish a share payload at most once per intent.
     *
     * The `savedInstanceState` gate in [onCreate] handles recreation; the
     * marker extra handles everything else that can hand us the same intent
     * object twice — `onNewIntent` for an intent we already took, and a
     * `getIntent()` re-read after the activity was rebuilt in this process.
     * `onNewIntent` still publishes normally: a genuinely new share arrives
     * as a new intent, without the marker.
     */
    private fun publishShareIfPresent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHARE_HANDLED, false)) return
        val payload = SharePayload.fromIntent(intent)?.foreignOnly(packageName) ?: return
        intent.putExtra(EXTRA_SHARE_HANDLED, true)
        sharePayloadHolder.publish(payload)
    }

    private companion object {
        /** Marks a share intent this activity has already handed to the holder. */
        const val EXTRA_SHARE_HANDLED = "com.megamaced.nccollectives.SHARE_HANDLED"
    }
}

/**
 * S9: keep page text out of the Recents thumbnail. Android 13 can leave out
 * just the thumbnail; before it, `FLAG_SECURE` is the only way, and it also
 * blocks screenshots and screen recording — which the setting says.
 */
internal fun Activity.applyHideInRecents(hide: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!hide)
    } else if (hide) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
