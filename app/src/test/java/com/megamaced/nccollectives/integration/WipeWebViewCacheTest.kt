package com.megamaced.nccollectives.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.WebViewHttpCache
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S-29: the platform glue behind the wipe's HTTP-cache step, under
 * Robolectric's WebView. It must not throw. `LocalDataWiper.wipe` itself
 * hops to the main looper, which a Robolectric test thread holds, so the
 * wiring is one plain line in `clearWebViewState` rather than a test here.
 */
@RunWith(AndroidJUnit4::class)
class WipeWebViewCacheTest {
    @Test
    fun clearingTheCache_runsOnARealWebView() {
        WebViewHttpCache(ApplicationProvider.getApplicationContext()).clear()
    }
}
