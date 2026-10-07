package com.megamaced.nccollectives.privacy

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README says the app talks only to the user's server. The system
 * WebView that hosts the collaborative editor reports usage metrics to
 * Google whenever the user has opted into usage and diagnostics on the
 * device, unless the app opts out in its manifest. Read from the merged
 * manifest, so a build variant or library that drops the flag fails here.
 */
@RunWith(AndroidJUnit4::class)
class WebViewReportingTest {
    @Test
    fun theEditorWebViewDoesNotSendUsageMetrics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metaData = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData

        assertEquals(true, metaData?.get("android.webkit.WebView.MetricsOptOut"))
    }
}
