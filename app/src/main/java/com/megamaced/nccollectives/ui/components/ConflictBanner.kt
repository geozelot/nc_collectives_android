package com.megamaced.nccollectives.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * B-78: "Discard" destroys `draftBodyMd` — the only copy of edits that lost
 * an If-Match race — and it sits one button-width from "Replace page". It
 * gets a confirmation dialog, the same as every other destructive action in
 * the app (page trash, collective trash, attachment delete).
 *
 * "Replace page" is destructive too, the other way round: it overwrites
 * whatever the server holds, including what someone else wrote since the
 * user started editing. It asks first as well.
 */
@Composable
fun ConflictBanner(
    draft: String,
    onReplace: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Unsaved changes on this device",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "The server's version changed after you started editing. Your local edits are kept as a draft.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(draft)) }) { Text("Copy") }
            TextButton(onClick = { confirmDiscard = true }) { Text("Discard") }
            TextButton(onClick = { confirmReplace = true }) { Text("Replace page") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your draft?") },
            text = {
                Text(
                    "Your unsaved edits will be deleted and the server's version kept. " +
                        "This can't be undone — use Copy first if you want to keep them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep draft") }
            },
        )
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace the page with your draft?") },
            text = {
                Text(
                    "The page on the server will be overwritten with your draft, including any changes " +
                        "someone else made since you started editing. Use Copy first if you want to compare.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    onReplace()
                }) { Text("Replace with my draft") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
            },
        )
    }
}
