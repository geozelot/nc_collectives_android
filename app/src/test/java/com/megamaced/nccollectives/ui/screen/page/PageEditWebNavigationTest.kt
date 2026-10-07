package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the navigation gate behind
 * [StripChromeWebViewClient.shouldOverrideUrlLoading] — [shouldKeepInWebView]
 * (host + scheme), [decideNavigation] (S-23's gesture / main-frame rule on
 * top of it) and [isAllowedExternalScheme] (what may be handed to the
 * system at all).
 *
 * Pins the link-routing contract adopted from nextcloud/notes-android
 * (`NoteDirectEditFragment`, commit 398abd51): only same-host `https`
 * navigations stay in the editor WebView; everything else leaves, so an
 * in-editor link (mention, file ref, `mailto:`, external URL) can't
 * hijack the edit session. Fails closed when either host is missing.
 */
class PageEditWebNavigationTest {
    private val host = "cloud.example.com"

    @Test
    fun sameHostHttps_staysInWebView() {
        assertTrue(shouldKeepInWebView(host, "https", host))
    }

    @Test
    fun sameHostHttps_isCaseInsensitive() {
        assertTrue(shouldKeepInWebView("Cloud.Example.Com", "HTTPS", host))
    }

    @Test
    fun differentHost_leavesWebView() {
        assertFalse(shouldKeepInWebView("evil.example.org", "https", host))
    }

    @Test
    fun sameHostHttp_leavesWebView() {
        // Non-https even on the right host is routed out — the app is
        // HTTPS-only (network_security_config cleartext=false) and the
        // editor session URL is always https.
        assertFalse(shouldKeepInWebView(host, "http", host))
    }

    @Test
    fun mailtoScheme_leavesWebView() {
        // `mailto:` (and tel:/geo:/intent:) parse with a null host.
        assertFalse(shouldKeepInWebView(null, "mailto", host))
    }

    @Test
    fun nullAllowedHost_failsClosed() {
        // Couldn't parse a host from the session URL — never keep a
        // navigation in the WebView rather than risk hijacking it.
        assertFalse(shouldKeepInWebView(host, "https", null))
    }

    @Test
    fun emptyAllowedHost_failsClosed() {
        assertFalse(shouldKeepInWebView(host, "https", ""))
    }

    @Test
    fun tappedExternalLinkInMainFrame_routesToSystem() {
        assertEquals(
            NavigationDecision.RouteToSystem,
            decideNavigation("example.org", "https", host, isForMainFrame = true, hasGesture = true),
        )
    }

    @Test
    fun scriptedExternalNavigation_isBlocked() {
        // S-23: a scripted `location` assignment carries no gesture, so it
        // must not be able to open a Custom Tab on its own.
        assertEquals(
            NavigationDecision.Block,
            decideNavigation("example.org", "https", host, isForMainFrame = true, hasGesture = false),
        )
    }

    @Test
    fun externalNavigationFromSubframe_isBlocked() {
        // A cross-origin iframe can't launch the system handler even if the
        // user did tap inside it.
        assertEquals(
            NavigationDecision.Block,
            decideNavigation("example.org", "https", host, isForMainFrame = false, hasGesture = true),
        )
    }

    @Test
    fun scriptedMailtoNavigation_isBlocked() {
        assertEquals(
            NavigationDecision.Block,
            decideNavigation(null, "mailto", host, isForMainFrame = true, hasGesture = false),
        )
    }

    @Test
    fun inSessionNavigation_staysInWebViewWithoutGesture() {
        // Text redirects and loads frames of its own with no touch involved —
        // the gesture rule must only ever gate *leaving* the WebView.
        assertEquals(
            NavigationDecision.KeepInWebView,
            decideNavigation(host, "https", host, isForMainFrame = false, hasGesture = false),
        )
    }

    @Test
    fun nullAllowedHost_blocksUngesturedNavigation() {
        assertEquals(
            NavigationDecision.Block,
            decideNavigation(host, "https", null, isForMainFrame = true, hasGesture = false),
        )
    }

    @Test
    fun allowlistedSchemes_mayReachTheSystem() {
        listOf("http", "https", "mailto", "tel", "geo").forEach { scheme ->
            assertTrue(scheme, isAllowedExternalScheme(scheme))
        }
    }

    @Test
    fun allowlistedSchemes_areCaseInsensitive() {
        assertTrue(isAllowedExternalScheme("HTTPS"))
    }

    @Test
    fun hostileSchemes_neverReachTheSystem() {
        // S-23: `intent:` in particular reaches any exported component that
        // claims it; the rest are only ever a way out of the sandbox.
        listOf("intent", "javascript", "file", "content", "app-scheme", null).forEach { scheme ->
            assertFalse(scheme.toString(), isAllowedExternalScheme(scheme))
        }
    }

    @Test
    fun theLoginPageOnTheServer_endsTheSession() {
        // S11: shown inside the editor, Nextcloud's login form would have
        // the user type their account password into a WebView the app
        // injects script into.
        listOf("/login", "/index.php/login", "/login/challenge/totp", "/index.php/login/v2/flow").forEach { path ->
            assertEquals(
                path,
                NavigationDecision.SessionExpired,
                decideNavigation(host, "https", host, isForMainFrame = true, hasGesture = false, targetPath = path),
            )
        }
    }

    @Test
    fun theLoginPageInASubframe_endsTheSessionToo() {
        assertEquals(
            NavigationDecision.SessionExpired,
            decideNavigation(host, "https", host, isForMainFrame = false, hasGesture = false, targetPath = "/login"),
        )
    }

    @Test
    fun theLoginPageUnderTheServersBasePath_endsTheSession() {
        assertEquals(
            NavigationDecision.SessionExpired,
            decideNavigation(
                host,
                "https",
                host,
                isForMainFrame = true,
                hasGesture = false,
                targetPath = "/nextcloud/index.php/login",
                allowedBasePath = "/nextcloud",
            ),
        )
    }

    @Test
    fun pathsThatOnlyLookLikeLogin_stayInTheEditor() {
        listOf("/loginx", "/apps/text/login", "/index.php/apps/login", "/").forEach { path ->
            assertEquals(
                path,
                NavigationDecision.KeepInWebView,
                decideNavigation(host, "https", host, isForMainFrame = true, hasGesture = false, targetPath = path),
            )
        }
        // Outside the server's base path it isn't this server's login page.
        assertEquals(
            NavigationDecision.KeepInWebView,
            decideNavigation(
                host,
                "https",
                host,
                isForMainFrame = true,
                hasGesture = false,
                targetPath = "/login",
                allowedBasePath = "/nextcloud",
            ),
        )
    }

    @Test
    fun aLoginPageOnAnotherHost_isRoutedLikeAnyOtherLink() {
        assertEquals(
            NavigationDecision.RouteToSystem,
            decideNavigation("example.org", "https", host, isForMainFrame = true, hasGesture = true, targetPath = "/login"),
        )
    }
}
