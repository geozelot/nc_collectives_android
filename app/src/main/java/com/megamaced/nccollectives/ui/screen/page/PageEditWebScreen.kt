package com.megamaced.nccollectives.ui.screen.page

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.ui.attachment.openInBrowser
import com.megamaced.nccollectives.ui.attachment.openWithSystemHandler
import com.megamaced.nccollectives.ui.theme.LocalTextScale
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Embedded Nextcloud Text editor backed by the Files `directediting` OCS
 * endpoint. Production entry routed from [PageViewScreen] when the
 * user's `EditorPreference` resolves to `Web` (Batch 29). Native
 * [PageEditScreen] is still the offline / older-server fallback.
 *
 * Lifecycle and behaviour mirror what the official Nextcloud Notes
 * Android app ships in `NoteDirectEditFragment` — see [DirectEditingMobileInterface]
 * for the JS-bridge contract.
 *
 * **Process-death (Batch 30f):** WebView state is intentionally not
 * `rememberSaveable`. The `directediting/open` token is consumed on
 * first WebView load, so a restore would 410 the request anyway. On
 * any restore we re-fetch a fresh URL (the ViewModel's `init` block
 * does this on every fresh VM instance). Any unsaved keystrokes that
 * Text hadn't autosaved server-side at the moment of process-death are
 * lost. Acceptable: Text autosaves on every few keystrokes, so the
 * lossy window is small. Documented here so future maintainers don't
 * try to `rememberSaveable` the URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageEditWebScreen(
    innerPadding: PaddingValues,
    onClose: () -> Unit,
    viewModel: PageEditWebViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Detect "is the rendered theme dark?" from the resolved M3 surface
    // colour rather than `isSystemInDarkTheme()` — the app honours the
    // user's per-app Theme preference (Settings → Theme), which can
    // diverge from the OS setting. Luminance < 0.5 is the standard
    // contrast-based dark/light split.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Theme T4: the editor wears the app's colours, in both modes.
    val colorScheme = MaterialTheme.colorScheme
    val injectionScript = remember(colorScheme, isDarkTheme) {
        buildInjectionScript(editorThemeCss(EditorPalette.from(colorScheme, isDarkTheme)))
    }

    // Text-size preference, translated into the WebView's own units. The
    // app's typography can't reach inside the WebView — Text ships its
    // own CSS — so `textZoom` is the only knob the two editors can share.
    val textZoom = textZoomPercent(LocalTextScale.current)

    // Holds the WebView reference for back-press JS injection (30d).
    // Set from the AndroidView factory; read by the BackHandler.
    var webView by remember { mutableStateOf<WebView?>(null) }

    // The editor kept animating and running media in the background. Pause
    // what `WebView.onPause` covers while the app isn't in front. Not
    // `pauseTimers()`: Text sends the user's last keystrokes from its own
    // timers, and stopping them could strand an edit on the device until the
    // app came back — or lose it, if the process didn't.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView?.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView?.onResume() }

    // Timestamp of the last back-press, milliseconds (30d). First back
    // press injects the Text close button; if the user back-presses
    // again within DOUBLE_BACK_WINDOW_MS we force-close in case Text
    // never came back with `close()` (slow autosave, network blip).
    var lastBackPressMs by remember { mutableStateOf(0L) }

    // Close-on-success: when the JS bridge has reported close() and the
    // ViewModel has flushed the refresh, pop back to PageView so the
    // observer-driven Flow picks up the autosaved body.
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Closed) onClose()
    }

    // Hard 10-second timeout matching the Notes-Android behaviour
    // (LOAD_TIMEOUT_SECONDS in `NoteDirectEditFragment.kt`).
    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Loaded) {
            delay(EDITOR_TIMEOUT_MS)
            if (viewModel.uiState.value is PageEditWebUiState.Loaded) {
                val result = snackbarHostState.showSnackbar(
                    message = "Editor is taking a long time to load",
                    actionLabel = "Cancel",
                )
                if (result == SnackbarResult.ActionPerformed) onClose()
            }
        }
    }

    LaunchedEffect(ui) {
        if (ui is PageEditWebUiState.Failed) {
            val msg = (ui as PageEditWebUiState.Failed).message
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.requestSession()
        }
    }

    // U8: Back and the toolbar arrow are one request. The arrow used to
    // call `onClose` directly, skipping the step that asks Text to save, so
    // whatever was typed inside Text's autosave debounce could be lost.
    val requestClose: () -> Unit = requestClose@{
        // B-47: a close is already in flight. Swallow the press rather than
        // letting it fall through and pop the screen out from under the
        // in-flight refresh — `viewModelScope` would cancel it and Room
        // would keep the pre-edit body.
        if (ui is PageEditWebUiState.Closing) {
            Timber.tag(TAG).d("Close request ignored while closing")
            return@requestClose
        }
        val now = System.currentTimeMillis()
        val current = webView
        val textIsRunning = current != null &&
            (ui is PageEditWebUiState.Loaded || ui is PageEditWebUiState.Interactive)
        if (!textIsRunning || now - lastBackPressMs < DOUBLE_BACK_WINDOW_MS) {
            // No editor running (nothing to save), or a second request —
            // leave now. Text's autosave should have flushed whatever was
            // typed within its debounce window, but anything in the gap is
            // lost. This is the documented escape hatch in case the JS
            // bridge never reports back.
            Timber.tag(TAG).d("Leaving the editor without waiting for Text")
            viewModel.leave()
        } else {
            // First request — ask Text to save + close via the same
            // `.icon-close` selector Notes-Android targets. The selector
            // is an upstream CSS contract (see DirectEditingMobileInterface
            // KDoc). When Text honours it, the JS bridge calls back into
            // our `close()` which routes through viewModel.onClose() →
            // state = Closed → the LaunchedEffect above pops the back stack.
            Timber.tag(TAG).d("First close request: injecting Text close")
            current.evaluateJavascript(JS_TEXT_CLOSE, null)
            lastBackPressMs = now
        }
    }

    BackHandler(onBack = requestClose)

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        // The outer `NcCollectivesScaffold` already consumed every system
        // bar inset into `innerPadding`; if the inner Scaffold also
        // applied its own status-bar inset (the default), the TopAppBar
        // would render below the consumed inset, leaving a visible gap
        // between the status bar and the title. Zero it out — every
        // inset is already handled one level up.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Edit (collaborative)", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    // Disabled while closing (B-47) so the only affordance
                    // that can re-enter `onClose` is visibly inert while the
                    // two refresh round-trips are in flight.
                    IconButton(
                        onClick = requestClose,
                        enabled = ui !is PageEditWebUiState.Closing,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                // The Scaffold's `contentWindowInsets = 0` zeros the
                // body padding, but `TopAppBar` keeps its own
                // `windowInsets` defaulting to the status-bar inset.
                // The outer NcCollectivesScaffold has already consumed
                // that inset into `innerPadding`, so without zeroing
                // here the bar renders pushed-down by the status-bar
                // height and a visible gap appears between the system
                // status bar and the "Edit (collaborative)" title.
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize(),
        ) {
            when (val state = ui) {
                is PageEditWebUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is PageEditWebUiState.Loaded, is PageEditWebUiState.Interactive -> {
                    EditorWebView(
                        url = (state as? PageEditWebUiState.Loaded)?.url
                            ?: (state as PageEditWebUiState.Interactive).url,
                        isInteractive = state is PageEditWebUiState.Interactive,
                        // Only the Loaded arm can carry the flag; by the time
                        // we reach Interactive the load has already happened.
                        clearCacheFirst = (state as? PageEditWebUiState.Loaded)?.clearCacheFirst == true,
                        isDarkTheme = isDarkTheme,
                        backgroundColor = MaterialTheme.colorScheme.background.toArgb(),
                        injectionScript = injectionScript,
                        textZoom = textZoom,
                        allowedHost = viewModel.allowedHost,
                        allowedBasePath = viewModel.serverBasePath,
                        onSessionExpired = viewModel::onSessionExpired,
                        onLoaded = viewModel::onEditorReady,
                        onCloseFromJs = viewModel::onClose,
                        onReloadFromJs = viewModel::onReloadRequested,
                        onSslError = {
                            viewModel.surfaceLoadFailure(
                                "Couldn't verify the server's TLS certificate. The editor was not opened.",
                            )
                        },
                        claimUrl = viewModel::claimUrl,
                        onUrlSpent = viewModel::onUrlSpent,
                        onWebViewCreated = { webView = it },
                        // Identity-checked rather than an unconditional
                        // `webView = null`: Compose gives no ordering
                        // guarantee between the old node's release and the
                        // new node's factory when the editor is remounted
                        // (B-46 reload path), and clearing the *new*
                        // instance would leave the back-press handler with
                        // nothing to inject Text's close into.
                        onWebViewReleased = { released ->
                            if (webView === released) webView = null
                        },
                    )
                }

                is PageEditWebUiState.Failed -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // B-47: the editor used to sit fully interactive-looking for
                // the length of two network round-trips after the user asked
                // to close. Replacing it with progress both tells the user
                // something is happening and takes the WebView (and its
                // back-press affordances) out of reach.
                PageEditWebUiState.Closing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Saving and closing…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                PageEditWebUiState.Closed -> {
                    Unit
                }
            }
        }
    }
}

// `JavascriptInterface` suppressed because lint can't follow the bridge's
// concrete type through Compose's generated wrapper — the methods *are*
// `@JavascriptInterface`-annotated, as `DirectEditingMobileInterface.kt`
// itself enforces. `SetJavaScriptEnabled` suppressed because the embedded
// editor is JS-driven by design.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun EditorWebView(
    url: String,
    isInteractive: Boolean,
    clearCacheFirst: Boolean,
    isDarkTheme: Boolean,
    backgroundColor: Int,
    injectionScript: String,
    textZoom: Int,
    allowedHost: String?,
    allowedBasePath: String,
    onSessionExpired: () -> Unit,
    onLoaded: () -> Unit,
    onCloseFromJs: () -> Unit,
    onReloadFromJs: () -> Unit,
    onSslError: () -> Unit,
    claimUrl: (String) -> Boolean,
    onUrlSpent: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
) {
    val context = LocalContext.current
    val bridge: DirectEditingMobileInterface =
        remember(onLoaded, onCloseFromJs, onReloadFromJs) {
            DirectEditingMobileInterface(
                onLoaded = onLoaded,
                onClose = onCloseFromJs,
                onShare = {},
                onReload = onReloadFromJs,
            )
        }

    // Links the Text editor renders (user mentions, file references,
    // embedded-image sources, `mailto:` in tables, external URLs) must not
    // be allowed to navigate the *editor* WebView itself — doing so tears
    // down the edit session. Only same-host https navigations stay in the
    // WebView; everything else is routed out to the system. Mirrors
    // nextcloud/notes-android `NoteDirectEditFragment` (commit 398abd51,
    // merged 2026-07-09).
    //
    // S-22: `allowedHost` is the host of the user's *stored* credentials,
    // passed down from the ViewModel. It used to be parsed back out of
    // [url] — i.e. out of server-supplied data — which let the allowlist
    // self-adjust to whatever host a hostile server named. `url` itself is
    // validated against the same stored host by
    // `DirectEditingRepositoryImpl` before it ever gets here.
    val openExternally: (Uri) -> Unit = remember(context) {
        { uri -> openExternalLink(context, uri) }
    }

    // Pending callback for the WebView's file chooser. The WebChromeClient
    // stashes the `ValueCallback` here, launches the system picker, and
    // the launcher's result handler invokes the callback with the chosen
    // URI(s) — or `null` if the user cancelled.
    val pendingFileCallback = remember { mutableStateOf<ValueCallback<Array<Uri>?>?>(null) }
    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // Even on cancel, the WebChromeClient demands `onReceiveValue`
        // be invoked (with `null`) or the picker stays in a permanent
        // "picking" state and subsequent clicks do nothing — Notes-
        // Android learned this the hard way, see Files-Android
        // `EditorWebView.java:140`.
        val callback = pendingFileCallback.value
        pendingFileCallback.value = null
        if (uri == null) {
            callback?.onReceiveValue(null)
        } else {
            callback?.onReceiveValue(arrayOf(uri))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // System navigation gesture bar sits at the very bottom of an
            // edge-to-edge window. Without this inset the WebView's
            // formatting toolbar slips behind the gesture indicator and
            // looks "squashed" against the bottom edge.
            //
            // The IME inset joins it because an edge-to-edge window ignores
            // the manifest's `adjustResize`: the WebView would keep its full
            // height and Text's fixed-bottom toolbar would sit under the
            // keyboard. Padding for it shrinks the WebView, and Text reflows
            // the toolbar above the keyboard on its own. `union` takes the
            // larger of the two rather than summing them — the IME inset
            // already spans the navigation bar it covers.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (!isInteractive) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Theme T2: a WebView paints white until its page does,
                    // which in a dark theme is a flash on every open.
                    setBackgroundColor(backgroundColor)
                    settings.apply {
                        javaScriptEnabled = true
                        // Load-bearing beyond DOM storage: Text v34+ persists
                        // the Yjs document in the WebView's **IndexedDB**
                        // (`useIndexedDbProvider.ts`, PR #7621) so unsaved
                        // edits survive a connectivity drop mid-session.
                        // Turning this off would silently take that away.
                        domStorageEnabled = true
                        // `cacheMode` is deliberately left at LOAD_DEFAULT.
                        // LOAD_CACHE_ELSE_NETWORK would serve Text's JS/CSS
                        // from cache more aggressively, but it applies to
                        // *every* subresource including the editor's XHR
                        // sync calls — answering one of those from a stale
                        // cache entry would corrupt the editing session.
                        // Asset caching is handled the safe way instead: the
                        // default HTTP cache keeps them, and
                        // `ServerVersionTracker` drops the cache when the
                        // server version changes (see `clearCacheFirst`).
                        userAgentString = "Mozilla/5.0 (Android) NCCollectives/${BuildConfig.VERSION_NAME} (Mobile)"
                        // Text-size preference (issue #6). `textZoom`
                        // rather than page zoom or a CSS `font-size`
                        // override: it reflows text at the WebView's
                        // width instead of making the user pan
                        // sideways, and it doesn't fight Text's own
                        // rem-based heading scale the way an injected
                        // `.ProseMirror { font-size }` rule would.
                        // Compounds with the OS font-size setting,
                        // which WebView already folds into its default
                        // — see `TextScale` for why the range stops
                        // where it does.
                        this.textZoom = textZoom
                        @Suppress("DEPRECATION")
                        allowFileAccess = false
                        // Nothing ever loads a `content://` URL in here — the
                        // only document is the server-hosted editor — so deny
                        // the WebView reach into other apps' content
                        // providers rather than leaving the default `true`.
                        allowContentAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    // The native vertical scrollbar inherits the Nextcloud
                    // theme colour and rendered as a wide vertical rail —
                    // the "blue bar" reported on-device. Killing it here
                    // (the WebView can still be panned/scrolled by touch)
                    // and the CSS injection below removes any
                    // `::-webkit-scrollbar` styling the page might add.
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    // Belt-and-braces darkening signal to the WebView
                    // engine: algorithmic darkening (Android 13+) only
                    // paints dark if the page opts in via
                    // `<meta name="color-scheme">` — Nextcloud Text
                    // doesn't, so this alone won't visually flip the
                    // theme. The real work is done by the CSS-variable
                    // override in `buildInjectionScript` below; this
                    // setting still helps for the slim WebView chrome
                    // (scrollbars, default form widgets) the page
                    // itself doesn't style.
                    if (isDarkTheme &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                    ) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                    }
                    addJavascriptInterface(bridge, DirectEditingMobileInterface.NAME)
                    webViewClient = StripChromeWebViewClient(
                        onSslError = onSslError,
                        injectionScript = injectionScript,
                        allowedHost = allowedHost,
                        allowedBasePath = allowedBasePath,
                        onExternalLink = openExternally,
                        onSessionExpired = onSessionExpired,
                    )
                    webChromeClient = ImagePickingChromeClient(
                        launchPicker = { callback ->
                            pendingFileCallback.value = callback
                            visualPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    if (clearCacheFirst) {
                        // Server upgraded since we last opened the editor, so
                        // any Text asset we're holding may be from the
                        // previous version. `clearCache` drops the HTTP cache
                        // only — IndexedDB (and with it Text's offline
                        // document state) is untouched, which is exactly the
                        // split we want. `clearBrowserData`-style wipes
                        // would throw away unsaved edits.
                        Timber.tag(TAG).d("Clearing WebView HTTP cache after server version change")
                        clearCache(true)
                    }
                    // H6: a WebView rebuilt after an Activity recreation
                    // finds its URL already spent by the one before it.
                    // Loading it again shows an error page, or Nextcloud's
                    // login form, so ask for a fresh session instead.
                    if (claimUrl(url)) loadUrl(url) else onUrlSpent()
                    onWebViewCreated(this)
                }
            },
            // The WebView outlives the factory, and `textZoom` applies
            // live without reloading the session, so re-apply it here
            // rather than leaving the editor stuck at whatever the
            // preference was when it opened.
            update = { webView ->
                webView.settings.textZoom = textZoom
                // Theme T4: a palette change (the Material You switch, a
                // wallpaper palette, the theme mode where the activity isn't
                // recreated) restyles the live editor instead of waiting for
                // the next page load, and without reloading the session.
                val client = webView.webViewClient as? StripChromeWebViewClient
                if (client != null && client.injectionScript != injectionScript) {
                    client.injectionScript = injectionScript
                    webView.evaluateJavascript(injectionScript, null)
                }
            },
            // B-46: the WebView has to be torn down explicitly when the
            // editor leaves composition — popped after `Closed`, or replaced
            // by the Loading arm when the JS bridge asks for a fresh session.
            // Without this its JS keeps running, so a reload leaves *two*
            // live Nextcloud Text sessions on the same document, and the
            // bridge's hard reference to the nav-scoped ViewModel leaks a
            // WebView plus a ViewModel per visit.
            //
            // `onRelease` is the right hook: it fires only when the view has
            // left the hierarchy for good, never on recomposition or
            // re-layout (reuse would go through `onReset`, which we don't
            // opt into). Order matters — drop the bridge and the chrome
            // client first so no late callback can reach a cleared
            // ViewModel, detach before destroying (the view system must not
            // be left holding a destroyed native instance), then destroy.
            onRelease = { view ->
                view.stopLoading()
                view.removeJavascriptInterface(DirectEditingMobileInterface.NAME)
                view.webChromeClient = null
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                onWebViewReleased(view)
            },
        )
    }
}

/**
 * Two responsibilities baked into one client:
 *
 *  1. **Strict TLS** — same posture as the rest of the app's
 *     `network_security_config.xml` (`cleartextTrafficPermitted="false"`).
 *     If a user's Nextcloud is on a self-signed CA, they must add it to
 *     the Android system store; the editor won't load over a cert the OS
 *     doesn't already trust.
 *  2. **Chrome strip** — Nextcloud's `directediting` URL loads the full
 *     Files-app shell around the Text editor: top header bar, left
 *     navigation, right details sidebar (which on a narrow viewport
 *     collapses into a slim coloured rail along the right edge — that's
 *     what the user reports as "blue bar"). The CSS below hides every
 *     known shell selector on `onPageFinished` so only the editor itself
 *     remains. Selectors are upstream Files / Server contracts; if they
 *     rename, the rail comes back but the editor itself still works.
 *     This is a deliberately additive `display: none` list — bias
 *     toward leaving unknown elements visible over hiding something
 *     useful.
 */
private class StripChromeWebViewClient(
    private val onSslError: () -> Unit,
    /**
     * Theme T4: replaced when the app's colours change. The page itself is
     * updated with `evaluateJavascript`, and this is what the next page load
     * injects.
     */
    var injectionScript: String,
    private val allowedHost: String?,
    private val allowedBasePath: String,
    private val onExternalLink: (Uri) -> Unit,
    private val onSessionExpired: () -> Unit,
) : WebViewClient() {
    /**
     * Keep only same-host `https` navigations inside the editor WebView;
     * route everything else out to the system browser / handler. Without
     * this, any link Text renders (mentions, file refs, `mailto:` in a
     * table, embedded-image sources, arbitrary external URLs) that the
     * user taps would navigate the editor WebView away from the edit
     * session and break it. Mirrors nextcloud/notes-android
     * `NoteDirectEditFragment.shouldOverrideUrlLoading` (398abd51).
     *
     * **S-23**: routing out is gated on the request being a *user-driven
     * main-frame* navigation. Consulting only `request.url` let a hostile
     * page auto-launch a Custom Tab or an implicit `ACTION_VIEW` with no
     * interaction at all — from a cross-origin iframe or a scripted
     * `location` assignment. Those are cancelled outright instead.
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        // Explicit null check rather than a safe call: the gesture and
        // main-frame flags below need `request` itself, and relying on a
        // smart cast through `request?.url ?: return` would be fragile.
        if (request == null) return false
        val target = request.url ?: return false
        val decision = decideNavigation(
            targetHost = target.host,
            targetScheme = target.scheme,
            allowedHost = allowedHost,
            isForMainFrame = request.isForMainFrame,
            hasGesture = request.hasGesture(),
            targetPath = target.path,
            allowedBasePath = allowedBasePath,
        )
        return when (decision) {
            NavigationDecision.KeepInWebView -> {
                false
            }

            // let the WebView load it — it's part of the edit session
            NavigationDecision.RouteToSystem -> {
                onExternalLink(target)
                true // consumed — don't navigate the editor away
            }

            NavigationDecision.SessionExpired -> {
                Timber.tag(TAG).i("Editor session reached the login page; asking for a new session")
                onSessionExpired()
                true
            }

            NavigationDecision.Block -> {
                // Host and scheme only: the session URL carries a one-shot
                // token we don't want in logcat.
                Timber.tag(TAG).d(
                    "Blocked un-gestured/subframe navigation to %s://%s",
                    target.scheme,
                    target.host,
                )
                true
            }
        }
    }

    /**
     * S-32: what the editor *loads*, as well as where it navigates.
     *
     * [shouldOverrideUrlLoading] gates navigations only. Every subresource a
     * page in the editor named still loaded from wherever it pointed. That
     * includes an image a collaborator embedded from a third-party host,
     * which the native viewer refuses (S-24), and which turns into a
     * tracking pixel the moment the page is opened for editing: a third
     * party learns the user's IP address and when they edited. Requests off
     * the user's server are answered here with an empty 403 and never leave
     * the device. Non-network schemes (`data:`, `blob:`) are not affected.
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (subresourceAllowed(url.scheme, url.host, allowedHost)) return null
        Timber.tag(TAG).d("Blocked a subresource from %s://%s", url.scheme, url.host)
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        // S9: the error's string form includes the URL, whose one-shot
        // session token must not reach logcat.
        Timber.tag(TAG).w("SSL error %d from WebView for host %s", error?.primaryError, error?.url?.toUri()?.host)
        handler?.cancel()
        onSslError()
    }

    /**
     * S11: the backstop for [shouldOverrideUrlLoading], which isn't
     * consulted for `loadUrl` itself or for a form POST. A main-frame load
     * of the server's login page is stopped before it renders.
     */
    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        val target = url?.toUri() ?: return
        val onTheServer = shouldKeepInWebView(target.host, target.scheme, allowedHost)
        if (onTheServer && isNextcloudLoginPath(target.path, allowedBasePath)) {
            view?.stopLoading()
            Timber.tag(TAG).i("Editor started loading the login page; asking for a new session")
            onSessionExpired()
        }
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(injectionScript, null)
    }
}

/**
 * Builds the JS payload injected on every `onPageFinished`. The IIFE:
 *
 *  - Inserts a `<style id="nc-collectives-strip">` element whose rules
 *    `display: none` the Files-app shell selectors we know wrap the
 *    embedded editor (top header — that's the "blue rail" on a narrow
 *    viewport — left navigation, right details sidebar, etc.).
 *  - Adds [editorThemeCss]'s `--color-*` overrides (Theme T4), which is
 *    what actually paints the editor in the app's colours. Algorithmic
 *    darkening alone is a no-op against pages that set colours through
 *    custom properties, which Nextcloud does throughout.
 *  - Installs a MutationObserver on `<body>` so the rules survive
 *    Vue's lazy mounts (Nextcloud assembles the shell in chunks after
 *    `onPageFinished` fires). Idempotent — re-running does nothing if
 *    the `<style>` is already present.
 *
 * Selectors and CSS variable names are upstream Server/Files/Text
 * contracts. If they rename, the rail or theme leak back but the
 * editor itself still works.
 */
internal fun buildInjectionScript(themeCss: String): String {
    val css = STRIP_CHROME_CSS + themeCss
    val escaped = css
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("'", "\\'")
    // Theme T4: the CSS lives on `window` so the MutationObserver installed by
    // the first injection applies the *current* CSS, and an existing <style>
    // is updated in place rather than kept, so a later injection (a palette
    // change) takes effect without a reload.
    return """
        (function() {
            var STYLE_ID = 'nc-collectives-strip';
            window.__ncCollectivesCss = '$escaped';
            function install() {
                var css = window.__ncCollectivesCss;
                var existing = document.getElementById(STYLE_ID);
                if (existing) {
                    if (existing.textContent !== css) existing.textContent = css;
                    return;
                }
                var head = document.head || document.documentElement;
                if (!head) return;
                var s = document.createElement('style');
                s.id = STYLE_ID;
                s.appendChild(document.createTextNode(css));
                head.appendChild(s);
            }
            install();
            if (!window.__ncCollectivesObserver && document.body) {
                window.__ncCollectivesObserver = new MutationObserver(install);
                window.__ncCollectivesObserver.observe(document.body, { childList: true, subtree: true });
            }
        })();
        """.trimIndent()
}

private const val STRIP_CHROME_CSS = """
#header, header#header, .header-right, .header-left { display: none !important; }
#app-navigation, #app-navigation-vue, nav#app-navigation { display: none !important; }
#app-sidebar, #app-sidebar-vue, aside.app-sidebar { display: none !important; }
.app-content-list, .files-controls, .breadcrumb { display: none !important; }
/* Belt-and-braces wildcard: anything Nextcloud names as a sidebar or
   activity rail, hide. Catches per-version selector renames upstream
   ships periodically. The editor itself is `.text-editor` / `#editor`
   so this won't blanket it. */
aside, [class*="-sidebar"], [class*="sidebar-"], .app-sidebar-toggle,
[class*="activity"], [class*="-activity-"] { display: none !important; }
#content, #content-vue, .app-content { padding: 0 !important; margin: 0 !important; top: 0 !important; left: 0 !important; width: 100% !important; }
body, html, #body-user { padding: 0 !important; margin: 0 !important; min-height: 100% !important; overflow-x: hidden !important; }
.text-editor, .editor, .text-editor__main, .ProseMirror { padding-top: 0 !important; }
/* WebKit scrollbars: with the WebView's native scrollbar disabled in
   Kotlin, this kills any in-page scrollbar Nextcloud styles via CSS
   (the page itself can still scroll via touch / overflow). */
::-webkit-scrollbar { width: 0 !important; height: 0 !important; background: transparent !important; }
::-webkit-scrollbar-thumb, ::-webkit-scrollbar-track { background: transparent !important; }
"""

/**
 * Theme T4: the app's colours, as an editor needs them. Built from the
 * resolved M3 [androidx.compose.material3.ColorScheme], so the editor follows
 * the app's palette (brand or Material You) and mode.
 */
internal data class EditorPalette(
    val isDark: Boolean,
    val background: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val error: Color,
) {
    companion object {
        fun from(
            scheme: androidx.compose.material3.ColorScheme,
            isDark: Boolean,
        ) = EditorPalette(
            isDark = isDark,
            background = scheme.background,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            surfaceContainerHighest = scheme.surfaceContainerHighest,
            outline = scheme.outline,
            outlineVariant = scheme.outlineVariant,
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            error = scheme.error,
        )
    }
}

/**
 * Theme T4: Nextcloud's `--color-*` tokens, mapped from the app's palette.
 *
 * Text reads its colours from these custom properties on `:root`, so setting
 * them once there cascades through every Vue component. They used to be set
 * in dark mode only, to hard-coded Nextcloud greys, for a subset of tokens:
 * whatever the subset missed kept its light value (light patches on a dark
 * page), and the accent was forced to grey. Light mode set nothing at all, so
 * a user whose Nextcloud theme is dark got a dark editor inside a light app.
 * Now both modes set the full set, from the colours the rest of the app is
 * drawn in. `color-scheme` flips the native form widgets with them.
 */
internal fun editorThemeCss(p: EditorPalette): String {
    val bg = p.background.hex()
    val text = p.onSurface.hex()
    return """
:root, html, body {
    color-scheme: ${if (p.isDark) "dark" else "light"} !important;
    --color-main-background: $bg !important;
    --color-main-background-rgb: ${p.background.rgb()} !important;
    --color-main-background-translucent: rgba(${p.background.rgb()}, 0.9) !important;
    --color-main-background-blur: rgba(${p.background.rgb()}, 0.7) !important;
    --color-background-hover: ${p.surfaceContainerHigh.hex()} !important;
    --color-background-dark: ${p.surfaceContainerHigh.hex()} !important;
    --color-background-darker: ${p.surfaceContainerHighest.hex()} !important;
    --color-placeholder-light: ${p.surfaceContainer.hex()} !important;
    --color-placeholder-dark: ${p.surfaceContainerHigh.hex()} !important;
    --color-main-text: $text !important;
    --color-text-light: $text !important;
    --color-text-lighter: ${p.onSurfaceVariant.hex()} !important;
    --color-text-maxcontrast: ${p.onSurfaceVariant.hex()} !important;
    --color-text-maxcontrast-default: ${p.onSurfaceVariant.hex()} !important;
    --color-border: ${p.outlineVariant.hex()} !important;
    --color-border-dark: ${p.outline.hex()} !important;
    --color-border-maxcontrast: ${p.outline.hex()} !important;
    --color-primary: ${p.primary.hex()} !important;
    --color-primary-default: ${p.primary.hex()} !important;
    --color-primary-text: ${p.onPrimary.hex()} !important;
    --color-primary-element: ${p.primary.hex()} !important;
    --color-primary-element-default: ${p.primary.hex()} !important;
    --color-primary-element-hover: ${p.primary.hex()} !important;
    --color-primary-element-text: ${p.onPrimary.hex()} !important;
    --color-primary-light: ${p.primaryContainer.hex()} !important;
    --color-primary-light-text: ${p.onPrimaryContainer.hex()} !important;
    --color-primary-element-light: ${p.primaryContainer.hex()} !important;
    --color-primary-element-light-text: ${p.onPrimaryContainer.hex()} !important;
    --color-error: ${p.error.hex()} !important;
    --image-background: none !important;
    --image-background-default: none !important;
    background-color: $bg !important;
    background-image: none !important;
    color: $text !important;
}
.text-editor, .editor, .text-editor__main, .ProseMirror, .ProseMirror * {
    background-color: transparent !important;
    color: $text !important;
}
.text-editor__wrapper, .text-editor__content-wrapper {
    background-color: $bg !important;
}
"""
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun Color.rgb(): String {
    val argb = toArgb()
    return "${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}"
}

/**
 * Surfaces the WebView's "insert image" file-chooser as the system
 * `PickVisualMedia` picker. Notes-Android *doesn't* install a chrome
 * client, so its in-editor image insert button is silently dead;
 * Files-Android does install one (`EditorWebView.java:140`). We side
 * with Files-Android — the image-insert button is useful, and routing
 * through `PickVisualMedia` avoids the runtime `READ_MEDIA_IMAGES`
 * permission prompt on Android 13+.
 */
private class ImagePickingChromeClient(
    private val launchPicker: (ValueCallback<Array<Uri>?>) -> Unit,
) : WebChromeClient() {
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>?>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback ?: return false
        launchPicker(filePathCallback)
        return true
    }
}

/**
 * Navigation gate for [StripChromeWebViewClient.shouldOverrideUrlLoading],
 * extracted as a pure function so the host-matching logic is unit-testable
 * without a WebView. A navigation stays inside the editor WebView only when
 * it targets the same host we opened the `directediting` session on, over
 * `https`. Anything else — a different host, a non-https scheme (`mailto:`,
 * `tel:`, `geo:`, `intent:`), or a null host — is routed out to the system.
 *
 * `allowedHost` null (no stored credentials, S-22) fails closed: nothing is
 * kept in the WebView, so links always leave rather than risk hijacking the
 * session.
 *
 * Host matching here is exact, deliberately narrower than the subdomain
 * tolerance `isSameServerHttpsUrl` applies to the session URL itself. A
 * server that hands back a session URL on a *subdomain* of the stored host
 * still opens — `loadUrl` isn't gated, and Text's subresources and XHRs
 * never reach this function — the only effect is that a tapped link back to
 * that subdomain opens in a Custom Tab instead of in the editor.
 */
internal fun shouldKeepInWebView(
    targetHost: String?,
    targetScheme: String?,
    allowedHost: String?,
): Boolean {
    if (allowedHost.isNullOrEmpty() || targetHost.isNullOrEmpty()) return false
    return targetScheme.equals("https", ignoreCase = true) &&
        targetHost.equals(allowedHost, ignoreCase = true)
}

/**
 * S-32: whether the editor WebView may fetch [scheme]://[host] for a page
 * element. https on the signed-in server, or a subdomain of it, which is the
 * same latitude the session URL itself is given. Anything else that would go
 * over the network is refused. Non-network schemes stay allowed. A null
 * [allowedHost] refuses every network request, failing closed like the
 * navigation gate does.
 */
internal fun subresourceAllowed(
    scheme: String?,
    host: String?,
    allowedHost: String?,
): Boolean {
    val lower = scheme?.lowercase() ?: return false
    if (lower != "http" && lower != "https") return true
    if (lower != "https" || allowedHost.isNullOrEmpty() || host.isNullOrEmpty()) return false
    return host.equals(allowedHost, ignoreCase = true) || host.endsWith(".$allowedHost", ignoreCase = true)
}

/** Outcome of [decideNavigation]. */
internal enum class NavigationDecision {
    /** Part of the edit session — let the WebView load it. */
    KeepInWebView,

    /** Hand it to the system (Custom Tab / `ACTION_VIEW`). */
    RouteToSystem,

    /** Cancel silently: neither the WebView nor the system sees it. */
    Block,

    /**
     * S11: the server's own login page. Cancel it and start a fresh
     * session rather than show a password form inside the editor.
     */
    SessionExpired,
}

/**
 * S11: whether [path] is Nextcloud's login page — the form itself, its
 * two-factor challenge, or a Login Flow page — on a server installed under
 * [basePath] (empty at the root). With or without `index.php`.
 */
internal fun isNextcloudLoginPath(
    path: String?,
    basePath: String,
): Boolean {
    var rest = path ?: return false
    val base = basePath.trimEnd('/')
    if (base.isNotEmpty()) {
        if (!rest.startsWith("$base/")) return false
        rest = rest.removePrefix(base)
    }
    rest = rest.removePrefix("/index.php")
    return rest == "/login" || rest.startsWith("/login/")
}

/**
 * Full navigation decision for
 * [StripChromeWebViewClient.shouldOverrideUrlLoading], pure so it is
 * unit-testable without a WebView. Layers S-23's gesture requirement over
 * [shouldKeepInWebView]'s host gate:
 *
 *  - the server's login page, in any frame, ends the session (S11);
 *  - in-session navigations ([shouldKeepInWebView]) stay in the WebView
 *    whatever fired them — Text redirects and loads subframes of its own,
 *    and none of that involves a touch;
 *  - anything else leaves for the system **only** when the user actually
 *    tapped it in the main frame;
 *  - everything remaining is cancelled. Without that last rule a hostile
 *    page could open a Custom Tab, or hand an arbitrary scheme to
 *    [openExternalLink]'s `ACTION_VIEW`, with no interaction — a
 *    cross-origin iframe or a scripted `location` assignment was enough.
 */
internal fun decideNavigation(
    targetHost: String?,
    targetScheme: String?,
    allowedHost: String?,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
    targetPath: String? = null,
    allowedBasePath: String = "",
): NavigationDecision =
    when {
        shouldKeepInWebView(targetHost, targetScheme, allowedHost) &&
            isNextcloudLoginPath(targetPath, allowedBasePath) -> NavigationDecision.SessionExpired

        shouldKeepInWebView(targetHost, targetScheme, allowedHost) -> NavigationDecision.KeepInWebView

        isForMainFrame && hasGesture -> NavigationDecision.RouteToSystem

        else -> NavigationDecision.Block
    }

/**
 * Maps a [com.megamaced.nccollectives.data.prefs.TextScale] multiplier to
 * the WebView's `textZoom` percentage. Extracted as a pure function so the
 * clamp is unit-testable without a WebView.
 *
 * Clamped to 50–200: `textZoom` accepts anything, and a bad value is a
 * silently unreadable editor rather than a crash. The app's own range
 * (85–140) sits well inside that, so the clamp only ever catches a
 * corrupted preference or a future step someone adds without reading
 * [PageEditWebScreen]'s notes on Text's px-laid-out toolbar.
 */
internal fun textZoomPercent(scale: Float): Int = (scale * 100f).roundToInt().coerceIn(50, 200)

/**
 * Schemes the editor is allowed to hand to the system (S-23). Everything
 * else — `intent:`, `content:`, `file:`, `javascript:`, an app's private
 * deep-link scheme — is dropped: handing an arbitrary scheme to an
 * implicit `ACTION_VIEW` lets a page in the editor reach any exported
 * component on the device that claims it.
 */
private val EXTERNAL_LINK_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")

/**
 * True when [scheme] is one the editor may route out to the system.
 * Pure so the allowlist is unit-testable.
 */
internal fun isAllowedExternalScheme(scheme: String?): Boolean = scheme != null && scheme.lowercase() in EXTERNAL_LINK_SCHEMES

/**
 * Routes a link the user tapped inside the editor out of the WebView.
 * `http(s)` opens in Chrome Custom Tabs (same as the rest of the app —
 * see [com.megamaced.nccollectives.util.handleMarkdownLink]); the other
 * allowlisted schemes (`mailto:`, `tel:`, `geo:`) go through a plain
 * `ACTION_VIEW`. Both are wrapped so a device with no handler for the
 * scheme logs and no-ops rather than crashing — matching notes-android's
 * try/catch.
 */
private fun openExternalLink(
    context: Context,
    uri: Uri,
) {
    val scheme = uri.scheme?.lowercase()
    if (!isAllowedExternalScheme(scheme)) {
        Timber.tag(TAG).d("Refusing to hand scheme '%s' to the system", scheme)
        return
    }
    val opened = if (scheme == "http" || scheme == "https") {
        openInBrowser(context, uri)
    } else {
        openWithSystemHandler(context, uri)
    }
    if (!opened) {
        Timber.tag(TAG).w("No handler for in-editor link: %s", uri)
    }
}

/**
 * JS snippet injected on the first back-press (Batch 30d). Mirrors what
 * `nextcloud/notes-android NoteDirectEditFragment.kt` does to ask Text
 * to save and close — clicks the editor's close button via CSS selector.
 * Upstream contract; if `.icon-close` is renamed in `nextcloud/text`,
 * the back button stops triggering save-and-close and the double-tap
 * force-close kicks in instead, so the user can still get out, but
 * unsaved keystrokes within the autosave debounce window are lost.
 */
private const val JS_TEXT_CLOSE = "document.querySelector('.icon-close')?.click();"

/**
 * 10-second timeout before we offer the user a way out. Same value as
 * Notes-Android's `LOAD_TIMEOUT_SECONDS` constant.
 */
private const val EDITOR_TIMEOUT_MS = 10_000L

/**
 * Window inside which a second back-press is interpreted as
 * "force-close, don't wait for Text to confirm". One second matches
 * the rhythm of typical double-tap gestures.
 */
private const val DOUBLE_BACK_WINDOW_MS = 1_000L

private const val TAG = "PageEditWebScreen"
