package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Scrollable despite always fitting: `Modifier.verticalScroll`
            // is what hands unconsumed drag to an enclosing
            // `PullToRefreshBox`. Without it the pull gesture is dead on
            // exactly the screens where refreshing matters most — an empty
            // list is the one you most want to retry (B-58).
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Same reason as EmptyState: keeps pull-to-refresh alive.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
    }
}

/**
 * The loading / error / empty / content switch a list screen shows (R-63).
 *
 * All three non-content arms are gated on [isEmpty] — that is what makes
 * them *first-load* states: once there are rows, a refresh in flight is a
 * spinner over the list, not a blank screen where the user's data was.
 *
 * A failure over rows is [StaleContentBanner] above them. It used to be
 * nothing: the comment here said "a snackbar", but no screen showed one, so
 * offline the user couldn't tell the list was stale, or that pulling to
 * refresh had failed. The banner stays until a refresh succeeds. [isEmpty] is passed in rather than derived from
 * a list because a screen's "has content" test can be wider than one
 * collection: the page tree has content when it has a landing page, even
 * with no tree rows.
 */
@Composable
fun ListStateSwitch(
    isLoading: Boolean,
    error: String?,
    isEmpty: Boolean,
    onRetry: (() -> Unit)?,
    empty: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isLoading && isEmpty -> {
            LoadingState()
        }

        error != null && isEmpty -> {
            ErrorState(message = error, onRetry = onRetry)
        }

        isEmpty -> {
            empty()
        }

        error != null -> {
            Column(modifier = Modifier.fillMaxSize()) {
                StaleContentBanner(message = error, onRetry = onRetry)
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }

        else -> {
            content()
        }
    }
}

/**
 * The last refresh failed, and what is on screen is what this device saved
 * before: says so, and why, with a way to try again.
 */
@Composable
fun StaleContentBanner(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${message.trimEnd().let { if (it.endsWith('.')) it else "$it." }} Showing what's saved on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
