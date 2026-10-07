package com.megamaced.nccollectives.ui.screen.login

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megamaced.nccollectives.data.auth.AccountSummary
import com.megamaced.nccollectives.ui.attachment.openInBrowser
import com.megamaced.nccollectives.ui.components.SnackbarStatusEffect
import timber.log.Timber

/**
 * Sign in to a Nextcloud server.
 *
 * Serves both the cold sign-in (mounted by the scaffold when there is no
 * session) and "add another account" from Settings (issue #14) — hence
 * [onCancel], which is null in the first case because there is nowhere to
 * go back to. The screen itself is otherwise identical between the two:
 * `AccountSwitcher.signInTo` works out from the credential store which of
 * them is happening.
 *
 * B-92: also the re-auth prompt, when [reauthAccount] is set. The server
 * rejected that account's credential, and signing in again to the same
 * account keeps everything cached for it, the edit queue included. The
 * server field is pinned to the account's host, because signing in anywhere
 * else would be a switch, and a switch wipes. [onRetry] tries the stored
 * credential again (for a passing outage). [onRemoveAccount] is the ordinary
 * removal, confirmed first, because it deletes [pendingEdits] unsynced edits.
 */
@Composable
fun LoginScreen(
    innerPadding: PaddingValues = PaddingValues(),
    onCancel: (() -> Unit)? = null,
    reauthAccount: AccountSummary? = null,
    pendingEdits: Int = 0,
    onRetry: (() -> Unit)? = null,
    onRemoveAccount: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.loginUrl) {
        uiState.loginUrl?.let { url ->
            // U13 + U16: opened, so stop offering it; or there is no browser.
            if (launchCustomTab(context, url)) viewModel.onLoginPageOpened() else viewModel.onBrowserUnavailable()
        }
    }

    LaunchedEffect(reauthAccount?.host) {
        reauthAccount?.let { viewModel.onHostChanged(it.host) }
    }

    if (confirmRemove && reauthAccount != null && onRemoveAccount != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${reauthAccount.loginName}?") },
            text = {
                Text(
                    if (pendingEdits > 0) {
                        "This deletes ${unsyncedEdits(pendingEdits)} from this device. They can't be recovered."
                    } else {
                        "This removes the account and its offline copy from this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveAccount()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }

    SnackbarStatusEffect(uiState.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        // Zero when the scaffold mounts this directly as the signed-out
        // screen; the authenticated host's padding when it is the "add
        // account" destination, same as every other screen in the nav host.
        modifier = Modifier.padding(innerPadding),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (reauthAccount != null) "Sign in again" else "NC Collectives",
                style = MaterialTheme.typography.headlineLarge,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (reauthAccount != null) {
                    "Your Nextcloud stopped accepting this device's sign-in for " +
                        "${reauthAccount.loginName}. " +
                        if (pendingEdits > 0) {
                            "${unsyncedEdits(pendingEdits).replaceFirstChar { it.uppercase() }} " +
                                "will be sent once you're signed in again."
                        } else {
                            "Your offline copy stays on this device."
                        }
                } else {
                    "Connect to your Nextcloud server"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = uiState.hostInput,
                onValueChange = viewModel::onHostChanged,
                label = { Text("Server URL") },
                placeholder = { Text("cloud.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.startLogin() }),
                enabled = reauthAccount == null && !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = viewModel::startLogin,
                enabled = !uiState.isLoading && !uiState.isPolling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (reauthAccount != null) "Sign in again" else "Log in")
                }
            }

            if (uiState.isPolling) {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for authorisation…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // U16: a way out if the browser was closed before signing in.
                TextButton(onClick = viewModel::cancelLogin) {
                    Text("Stop waiting")
                }
            }

            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onRetry,
                    enabled = !uiState.isLoading && !uiState.isPolling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Try again")
                }
            }

            if (onRemoveAccount != null) {
                TextButton(
                    onClick = { confirmRemove = true },
                    enabled = !uiState.isLoading && !uiState.isPolling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove account")
                }
            }

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                // Disabled mid-flow: the Custom Tab is already open and the
                // poll is running, and backing out from under it would leave
                // an app password issued server-side that this device never
                // stored.
                TextButton(
                    onClick = onCancel,
                    enabled = !uiState.isLoading && !uiState.isPolling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

private fun unsyncedEdits(count: Int): String = if (count == 1) "1 unsynced edit" else "$count unsynced edits"

/** False when nothing was opened: a refused URL, or no browser on the device (U13). */
private fun launchCustomTab(
    context: Context,
    url: String,
): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    // S-26: `launchUrl` resolves whatever scheme it is handed through the
    // system, so a server-supplied `intent:` / custom-scheme URL would
    // start another app rather than a browser tab. `LoginViewModel` already
    // refuses a login URL that isn't https on the host the user typed; this
    // gate is the scheme half, held locally where the launch happens.
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
        Timber.w("Refusing to open a login URL with scheme=%s", uri?.scheme)
        return false
    }
    // U13: `launchUrl` throws on a device with no browser at all, which took
    // the whole app down at the first sign-in.
    return openInBrowser(context, uri)
}
