package com.megamaced.nccollectives.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * S9: refuse touches that reach the surrounding window through another
 * app's window drawn over it (tapjacking).
 *
 * `MainActivity` sets this on its own window. A dialog is a window of its
 * own, so each one calls this from inside its content — the dialogs are
 * where the destructive confirmations are (remove an account, delete for
 * good, discard a draft). Android 12 and later refuse such touches from
 * untrusted overlays anyway; this covers 10 and 11.
 */
@Composable
fun RefuseObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        onDispose { }
    }
}
