package com.megamaced.nccollectives.ui.screen.share

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.model.PageListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareCaptureScreen(
    innerPadding: PaddingValues,
    onDismiss: () -> Unit,
    viewModel: ShareCaptureViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val collectives by viewModel.collectives.collectAsStateWithLifecycle()
    val pages by viewModel.pagesForCollective.collectAsStateWithLifecycle()

    // Auto-pick the only collective if there's just one (the common case).
    LaunchedEffect(collectives) {
        if (ui.selectedCollectiveId == null && collectives.size == 1) {
            viewModel.selectCollective(collectives.first().id)
        }
    }

    val context = LocalContext.current
    LaunchedEffect(ui.finished) {
        if (ui.finished) {
            // U9: the report used to go only into this screen's own text,
            // which left with the screen in the same frame. "Saved as X —
            // 2 images couldn't be read" was composed and never seen, and a
            // partial capture looked like a clean one. A toast outlives the
            // dismissal.
            ui.finishedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            onDismiss()
        }
    }

    // U1: backing out consumes the share, or it comes back on the next
    // rotation or process restore.
    val cancel: () -> Unit = {
        viewModel.cancel()
        onDismiss()
    }
    BackHandler(onBack = cancel)

    Scaffold(
        modifier = Modifier.padding(innerPadding),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Share to collective", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = cancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // The action and what it reports stay in view: they used to sit
        // below the last page of the collective, which in a large one was a
        // long scroll away.
        bottomBar = {
            if (ui.payload != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = viewModel::submit,
                        enabled = !ui.isSaving &&
                            ui.selectedCollectiveId != null &&
                            (ui.mode == ShareMode.NEW_PAGE || ui.selectedAppendPageId != null),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (ui.isSaving) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Text(if (ui.mode == ShareMode.NEW_PAGE) "Create page" else "Append")
                        }
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        val payload = ui.payload
        if (payload == null) {
            Text(
                text = "Nothing to share — return to the previous app and try again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(scaffoldPadding)
                    .padding(20.dp),
            )
            return@Scaffold
        }
        // Lazy, so a collective of hundreds of pages composes the rows on
        // screen rather than all of them.
        LazyColumn(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SharedContentPreview(payload, modifier = Modifier.fillMaxWidth()) }
            item {
                CollectivePicker(
                    collectives = collectives,
                    selectedId = ui.selectedCollectiveId,
                    onSelect = viewModel::selectCollective,
                )
            }
            item { ModeToggle(mode = ui.mode, onChange = viewModel::setMode) }
            when (ui.mode) {
                ShareMode.NEW_PAGE -> newPageSection(
                    title = ui.title,
                    onTitleChange = viewModel::setTitle,
                    pages = pages,
                    selectedParentId = ui.selectedParentPageId,
                    onParentChange = viewModel::selectParent,
                )

                ShareMode.APPEND -> appendSection(
                    pages = pages,
                    selectedPageId = ui.selectedAppendPageId,
                    onSelect = viewModel::selectAppendTarget,
                )
            }
        }
    }
}

@Composable
private fun SharedContentPreview(
    payload: com.megamaced.nccollectives.share.SharePayload,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("From the share sheet", style = MaterialTheme.typography.labelMedium)
        payload.text?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(500), style = MaterialTheme.typography.bodyMedium)
        }
        if (payload.images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Issue #38: `SharePayload.fromIntent` doesn't deduplicate
                // EXTRA_STREAM against ClipData, so a sender can legally
                // include the same content URI twice and two siblings then
                // shared a key — which a lazy layout throws on. Keyed by
                // occurrence, so duplicates stay distinct.
                itemsIndexed(payload.images, key = { index, uri -> "$index-$uri" }) { _, uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(80.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectivePicker(
    collectives: List<Collective>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = collectives.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Collective") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            collectives.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    onClick = {
                        onSelect(c.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(
    mode: ShareMode,
    onChange: (ShareMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ShareMode.NEW_PAGE,
            onClick = { onChange(ShareMode.NEW_PAGE) },
            label = { Text("New page") },
        )
        FilterChip(
            selected = mode == ShareMode.APPEND,
            onClick = { onChange(ShareMode.APPEND) },
            label = { Text("Append to existing") },
        )
    }
}

private fun LazyListScope.newPageSection(
    title: String,
    onTitleChange: (String) -> Unit,
    pages: List<PageListItem>,
    selectedParentId: Long?,
    onParentChange: (Long) -> Unit,
) {
    item {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Page title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
    item { Text("Parent page", style = MaterialTheme.typography.labelMedium) }
    item { HorizontalDivider() }
    if (pages.isEmpty()) {
        item {
            Text(
                text = "No pages in this collective yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(pages, key = { it.id }) { page ->
            // Any page is a valid parent — the server promotes a leaf
            // parent to a folder when it gains a child (Batch 18h).
            PageSelectableRow(
                page = page,
                selected = page.id == selectedParentId,
                onClick = { onParentChange(page.id) },
            )
        }
    }
}

private fun LazyListScope.appendSection(
    pages: List<PageListItem>,
    selectedPageId: Long?,
    onSelect: (Long) -> Unit,
) {
    item { Text("Append to", style = MaterialTheme.typography.labelMedium) }
    item { HorizontalDivider() }
    if (pages.isEmpty()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    } else {
        items(pages, key = { it.id }) { page ->
            PageSelectableRow(
                page = page,
                selected = page.id == selectedPageId,
                onClick = { onSelect(page.id) },
            )
        }
    }
}

@Composable
private fun PageSelectableRow(
    page: PageListItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = listOfNotNull(
                    page.emoji?.takeIf { it.isNotBlank() },
                    page.title,
                ).joinToString(" "),
            )
        },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
