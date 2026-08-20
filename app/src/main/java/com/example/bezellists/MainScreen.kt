package com.example.bezellists

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.json.JSONObject

private enum class SearchMode(val label: String, val icon: ImageVector) {
    All("searching all fields", Icons.Default.SelectAll),
    Title("searching titles only", Icons.Default.Title),
    Body("searching descriptions only", Icons.AutoMirrored.Filled.Notes),
}

/** Cards under a search bar; list picker left, dots right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    items: List<JSONObject>,
    lists: List<String>,
    selected: String?,
    haveData: Boolean,
    statusLine: String?,
    onSelect: (String?) -> Unit,
    onNewList: (String) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onDelete: (JSONObject) -> Unit,
) {
    var listMenu by remember { mutableStateOf(false) }
    var dotsMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var newListDialog by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf<JSONObject?>(null) }
    var confirmDelete by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.All) }
    // Text and visibility are separate so the pill fades out with its
    // text intact instead of collapsing around vanishing characters.
    var noticeText by remember { mutableStateOf("") }
    var noticeVisible by remember { mutableStateOf(false) }
    var noticeStamp by remember { mutableStateOf(0) }

    fun announce(text: String) { noticeText = text; noticeVisible = true; noticeStamp += 1 }
    LaunchedEffect(noticeStamp) {
        if (noticeVisible) { delay(1600); noticeVisible = false }
    }

    fun matches(item: JSONObject): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val b = item.getJSONObject("body")
        return when (mode) {
            SearchMode.Title -> b.getString("name").lowercase().contains(q)
            SearchMode.Body -> b.optString("description").lowercase().contains(q)
            SearchMode.All -> {
                val attrs = b.optJSONObject("attributes")?.let { a ->
                    a.keys().asSequence().joinToString(" ") { k -> "$k ${a.get(k)}" }
                } ?: ""
                listOf(
                    b.getString("name"), b.optString("description"),
                    b.optString("link"), attrs,
                ).any { it.lowercase().contains(q) }
            }
        }
    }

    // Query filtering happens per-card via AnimatedVisibility so matches
    // fade/collapse smoothly instead of snapping out of existence.
    val inList = items
        .filter { selected == null || it.getJSONObject("body").getString("list") == selected }
        .sortedBy { it.getJSONObject("body").getString("name").lowercase() }
    val matchCount = inList.count(::matches)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------ top bar:
            // [list picker] [rounded search + mode toggle] [dots]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    FilledTonalIconButton(
                        onClick = { listMenu = true },
                        shape = MaterialTheme.shapes.large, // matches the FAB
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "choose list")
                    }
                    DropdownMenu(
                        expanded = listMenu,
                        onDismissRequest = { listMenu = false },
                        modifier = Modifier.fillMaxWidth(0.8f),
                    ) {
                        ListMenuItem("All", items.size, selected == null) { onSelect(null); listMenu = false }
                        lists.forEach { name ->
                            val n = items.count { it.getJSONObject("body").getString("list") == name }
                            ListMenuItem(name, n, selected == name) { onSelect(name); listMenu = false }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("New list…", style = MaterialTheme.typography.titleMedium) },
                            onClick = { listMenu = false; newListDialog = true },
                        )
                    }
                }

                OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Search ${selected ?: "All"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = {
                        if (query.isEmpty()) {
                            Icon(Icons.Default.Search, contentDescription = null)
                        } else {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "clear search")
                            }
                        }
                    },
                    trailingIcon = {
                        Box {
                            // Tap cycles the mode; press-hold picks from a list.
                            Box(
                                Modifier
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .combinedClickable(
                                        onClick = {
                                            mode = SearchMode.entries[
                                                (mode.ordinal + 1) % SearchMode.entries.size]
                                            announce(mode.label)
                                        },
                                        onLongClick = { modeMenu = true },
                                    )
                                    .padding(8.dp),
                            ) {
                                Icon(mode.icon, contentDescription = mode.label)
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                SearchMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label) },
                                        leadingIcon = { Icon(m.icon, contentDescription = null) },
                                        onClick = { mode = m; announce(m.label); modeMenu = false },
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )

                Box {
                    FilledTonalIconButton(
                        onClick = { dotsMenu = true },
                        shape = MaterialTheme.shapes.large, // matches the FAB
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "menu")
                    }
                    DropdownMenu(expanded = dotsMenu, onDismissRequest = { dotsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { dotsMenu = false; onSettings() },
                        )
                    }
                }
            }

            statusLine?.let {
                Text(it, Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ------------------------------------------------ cards
            if (!haveData) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("syncing over iroh…", Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // A plain scroll column kept 1dp taller than the viewport:
                // the stretch overscroll engages even on short lists.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val minHeight = maxHeight + 1.dp
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = minHeight),
                    ) {
                        inList.forEach { item ->
                            AnimatedVisibility(
                                visible = matches(item),
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                EntryCard(
                                    item = item,
                                    showList = selected == null,
                                    onOpen = { onOpen(item) },
                                    onLongPress = { longPressed = item },
                                )
                            }
                        }
                        if (matchCount == 0) {
                            Text(
                                if (query.isBlank()) "nothing here yet" else "no matches",
                                Modifier.fillMaxWidth().padding(32.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(88.dp)) // clears the FAB
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "add entry") }

        // The transient "what mode am I in" pill: an overlay, so it never
        // displaces content, and its text survives the fade-out.
        AnimatedVisibility(
            visible = noticeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 3.dp,
            ) {
                Text(
                    noticeText,
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (newListDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newListDialog = false },
            title = { Text("New list") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) { onNewList(name.trim()); newListDialog = false }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton({ newListDialog = false }) { Text("Cancel") } },
        )
    }

    longPressed?.let { item ->
        EntryActions(
            item = item,
            onDismiss = { longPressed = null },
            onDelete = { longPressed = null; confirmDelete = item },
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${item.getJSONObject("body").getString("name")}\"?") },
            text = { Text("This removes the entry from the core. Its history is kept.") },
            confirmButton = {
                TextButton({ onDelete(item); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListMenuItem(name: String, count: Int, active: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text("$count", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(
    item: JSONObject,
    showList: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val b = item.getJSONObject("body")
    val pending = item.getString("id").startsWith("pending-")
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    b.getString("name") + if (pending) "  ⋯" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (showList) {
                    Text(b.getString("list"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            b.optString("description").ifBlank { null }?.let {
                Text(
                    markdownToAnnotated(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ItemImage(b)
        }
    }
}

/** The press-hold menu: copy things, or delete (confirmed by the caller). */
@Composable
private fun EntryActions(item: JSONObject, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val b = item.getJSONObject("body")
    val clipboard = LocalClipboardManager.current
    val name = b.getString("name")
    val desc = b.optString("description").ifBlank { null }
    val link = b.optString("link").ifBlank { null }
    val image = explicitImage(b)

    fun copy(s: String) { clipboard.setText(AnnotatedString(s)); onDismiss() }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(name, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                ActionRow("Copy title") { copy(name) }
                desc?.let { ActionRow("Copy description") { copy(it) } }
                desc?.let { ActionRow("Copy title + description") { copy("$name\n\n$it") } }
                link?.let { ActionRow("Copy link") { copy(it) } }
                image?.let { ActionRow("Copy image URL") { copy(it) } }
                HorizontalDivider()
                ActionRow("Delete", danger = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
