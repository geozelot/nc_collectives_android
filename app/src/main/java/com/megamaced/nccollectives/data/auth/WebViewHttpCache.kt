package com.megamaced.nccollectives.data.auth

import android.content.Context
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S-29: the collaborative editor's HTTP disk cache, which is per app, not per
 * WebView.
 *
 * It holds whatever Nextcloud Text served the editor, cached under the
 * outgoing account's session: page content, avatars, attachment previews.
 * `WebView.clearCache` is the only API that empties it, and it needs a
 * WebView instance, so one is made for the purpose and destroyed straight
 * after. Main thread only, like every WebView call.
 */
@Singleton
class WebViewHttpCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun clear() {
            val webView = WebView(context)
            try {
                webView.clearCache(true)
            } finally {
                webView.destroy()
            }
        }
    }
