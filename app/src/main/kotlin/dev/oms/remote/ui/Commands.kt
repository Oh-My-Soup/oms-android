package dev.oms.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.CommandInfo
import dev.oms.remote.protocol.api.CommandRun
import dev.oms.remote.protocol.api.CommandRunStatus
import dev.oms.remote.protocol.api.CompletionItem
import dev.oms.remote.protocol.api.ModelCatalog
import dev.oms.remote.protocol.api.ModelInfo
import dev.oms.remote.protocol.api.SubcommandInfo
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Slash completion
// ---------------------------------------------------------------------------------------------

/** A completion row: tapping it replaces the draft with [insert]. */
internal data class SlashSuggestion(val insert: String, val label: String, val hint: String?, val description: String?)

private const val MaxSuggestions = 8

/**
 * Completions for a draft that starts with `/`: command names (and aliases) while the first word
 * is typed, then that command's subcommands. Empty when nothing matches or the draft is not a command.
 */
internal fun slashSuggestions(draft: String, commands: List<CommandInfo>): List<SlashSuggestion> {
    if (!draft.startsWith("/")) return emptyList()
    val body = draft.drop(1)
    val space = body.indexOf(' ')
    if (space < 0) {
        val prefix = body.lowercase()
        val matches = commands.filter { command ->
            command.name.lowercase().startsWith(prefix) || command.aliases.any { it.lowercase().startsWith(prefix) }
        }
        // An exact, argument-free command needs no completion: the user can just send it.
        if (matches.size == 1 && matches[0].name == body && !matches[0].takesArgs) return emptyList()
        return matches
            .sortedBy { if (it.name.lowercase().startsWith(prefix)) 0 else 1 }
            .take(MaxSuggestions)
            .map { SlashSuggestion("/${it.name}${if (it.takesArgs) " " else ""}", "/${it.name}", it.hint, it.description) }
    }
    val name = body.substring(0, space)
    val command = commands.firstOrNull { it.name == name || name in it.aliases } ?: return emptyList()
    val rest = body.substring(space + 1)
    if (rest.contains(' ')) return emptyList()
    val subs: List<SubcommandInfo> = command.subcommands.filter { it.name.startsWith(rest, ignoreCase = true) }
    if (subs.size == 1 && subs[0].name == rest) return emptyList()
    return subs.take(MaxSuggestions).map { sub ->
        SlashSuggestion("/${command.name} ${sub.name} ", sub.name, sub.usage, sub.description)
    }
}

/** Rows for the host's argument completions of [draft]: each replaces everything after the command name and its space. */
internal fun argumentSuggestions(draft: String, items: List<CompletionItem>): List<SlashSuggestion> {
    val head = draft.substring(0, draft.indexOf(' ') + 1)
    // The draft already is the only completion: nothing left to offer.
    if (items.size == 1 && head + items[0].value == draft) return emptyList()
    // Rows are keyed by what they insert, so two completions with the same value show once.
    return items.map { SlashSuggestion(head + it.value, it.label, null, it.description) }
        .distinctBy { it.insert }
        .take(MaxSuggestions)
}

/** The completion list shown above the composer's text field. */
@Composable
internal fun SlashSuggestionList(suggestions: List<SlashSuggestion>, onPick: (SlashSuggestion) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(suggestions, key = { it.insert }) { suggestion ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(suggestion.label, style = OmsStyle.mono, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                        if (!suggestion.hint.isNullOrBlank()) {
                            Text(suggestion.hint, style = OmsStyle.monoSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (!suggestion.description.isNullOrBlank()) {
                        Text(
                            suggestion.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Shown under a `/` draft when the host cannot run commands from guests. */
@Composable
internal fun CommandsUnsupportedHint() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "This oms can't run commands from the phone, so this is sent to the model as a message. " +
                    "Update oms on the computer to use commands.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Command palette
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommandPaletteSheet(
    commands: List<CommandInfo>?,
    onDismiss: () -> Unit,
    onPick: (CommandInfo) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(commands, query) {
        val q = query.trim().removePrefix("/").lowercase()
        commands.orEmpty().filter { command ->
            q.isEmpty() || command.name.lowercase().contains(q) || command.aliases.any { it.lowercase().contains(q) } ||
                command.description?.lowercase()?.contains(q) == true
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Commands", style = MaterialTheme.typography.titleLarge)
            SearchField(query, { query = it }, "Search commands", Modifier.padding(top = 12.dp, bottom = 8.dp))
        }
        if (commands == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered, key = { it.name }) { command ->
                CommandRow(command, onClick = { onPick(command) })
            }
            if (filtered.isEmpty()) {
                item(key = "__empty") {
                    Text(
                        "No command matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: CommandInfo, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("/${command.name}", style = OmsStyle.mono, fontWeight = FontWeight.SemiBold, color = scheme.primary)
            command.hint?.let { Text(it, style = OmsStyle.monoSmall, color = scheme.onSurfaceVariant) }
            if (command.source != "builtin") SourceTag(command.source)
        }
        val description = command.description
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceTag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Command results
// ---------------------------------------------------------------------------------------------

/** How long a successful run without output stays visible. */
private const val QuietRunMs = 2_500L

/** The latest command/model/thinking request and the host's answer, above the composer. */
@Composable
internal fun CommandRunCard(run: CommandRun, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(run.id) { mutableStateOf(false) }
    val quiet = run.status == CommandRunStatus.Done && run.output == null && run.error == null
    LaunchedEffect(run.id, quiet) {
        if (quiet) {
            delay(QuietRunMs)
            onDismiss()
        }
    }
    val failed = run.status == CommandRunStatus.Failed
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (failed) scheme.errorContainer else scheme.surfaceContainerHighest,
        contentColor = if (failed) scheme.onErrorContainer else scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (run.status) {
                    CommandRunStatus.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    CommandRunStatus.Done -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = OmsStyle.status.live)
                    CommandRunStatus.Failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp))
                }
                Text(
                    run.label,
                    style = OmsStyle.mono,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (run.status != CommandRunStatus.Running) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                } else {
                    // Stops a command still producing output (a streaming /btw answer); others finish anyway.
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                    }
                }
            }
            val body = listOfNotNull(run.error, run.output).joinToString("\n\n")
            if (body.isNotBlank()) {
                // A one-line message wraps; a multi-line report keeps its columns and scrolls sideways.
                val report = body.contains('\n')
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .heightIn(max = if (expanded) 360.dp else 120.dp)
                        .verticalScroll(rememberScrollState())
                        .then(if (report) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .clickable { expanded = !expanded },
                ) {
                    Text(body, style = OmsStyle.monoSmall, softWrap = !report)
                }
            } else if (run.status == CommandRunStatus.Running) {
                Text("Running on the computer…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Model & thinking
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    supported: Boolean,
    /** Without the picker, the host still takes `/switch` and `/effort` typed as commands. */
    commandsAvailable: Boolean,
    catalog: ModelCatalog?,
    loading: Boolean,
    currentModelName: String?,
    currentThinking: String?,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onPickModel: (ModelInfo) -> Unit,
    onPickThinking: (String) -> Unit,
    /** Sends the search to the host for a [ModelCatalog.searchOnHost] catalog. */
    onSearch: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { if (supported) onLoad() }
    var query by rememberSaveable { mutableStateOf("") }
    val searchOnHost = catalog?.searchOnHost == true
    LaunchedEffect(query, searchOnHost) {
        if (!searchOnHost) return@LaunchedEffect
        delay(ModelSearchDebounceMs)
        onSearch(query)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Model & thinking", style = MaterialTheme.typography.titleLarge)
            if (currentModelName != null) {
                Text(
                    "Now: $currentModelName${currentThinking?.let { " · thinking $it" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!supported) {
            Text(
                if (commandsAvailable) {
                    "This oms doesn't let the phone switch models or thinking. Update oms on the computer to use this."
                } else {
                    "This oms can't switch models from the phone. Update oms on the computer to use this."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@ModalBottomSheet
        }
        if (catalog == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else TextButton(onClick = onLoad) { Text("Load models") }
            }
            return@ModalBottomSheet
        }
        val thinking = catalog.thinkingLevel ?: currentThinking
        if (catalog.thinkingLevels.isNotEmpty()) {
            Row(
                Modifier.padding(start = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Thinking", style = MaterialTheme.typography.titleSmall)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (level in catalog.thinkingLevels) {
                    FilterChip(
                        selected = level == thinking,
                        onClick = { if (level != thinking) onPickThinking(level) },
                        label = { Text(level) },
                    )
                }
            }
        }
        SearchField(query, { query = it }, "Search models", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val q = query.trim().lowercase()
        val matches = remember(catalog, q) {
            // A host-searched catalog already holds the host's matches for the query, best first.
            if (catalog.searchOnHost) {
                catalog.models
            } else {
                catalog.models.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.key.lowercase().contains(q) }
            }
        }
        val byKey = remember(catalog) { catalog.models.associateBy { it.key } }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            val roles = catalog.roles.filter { role ->
                q.isEmpty() || role.role.contains(q) || role.key.lowercase().contains(q) ||
                    byKey[role.key]?.name?.lowercase()?.contains(q) == true
            }
            if (roles.isNotEmpty()) {
                item(key = "__roles") { ModelSectionLabel("Roles") }
                items(roles, key = { "role:${it.role}" }) { role ->
                    val model = byKey[role.key] ?: ModelInfo(role.provider, role.id, role.id, reasoning = false)
                    ModelRow(model, current = model.key == catalog.currentKey, role = role.role, onClick = { onPickModel(model) })
                }
            }
            if (catalog.searchOnHost) {
                if (matches.isNotEmpty()) item(key = "__ranked") { ModelSectionLabel(if (q.isEmpty()) "Models" else "Matches") }
                items(matches, key = { "ranked:${it.key}" }) { model ->
                    ModelRow(model, current = model.key == catalog.currentKey, onClick = { onPickModel(model) })
                }
            }
            val scoped = if (catalog.searchOnHost) emptyList() else matches.filter { it.scoped }
            if (scoped.isNotEmpty()) {
                item(key = "__scoped") { ModelSectionLabel("Enabled models") }
                items(scoped, key = { "scoped:${it.key}" }) { model ->
                    ModelRow(model, current = model.key == catalog.currentKey, onClick = { onPickModel(model) })
                }
            }
            val byProvider = if (catalog.searchOnHost) emptyMap() else matches.groupBy { it.provider }.toSortedMap()
            for ((provider, models) in byProvider) {
                item(key = "__provider:$provider") { ModelSectionLabel(provider) }
                items(models, key = { "all:${it.key}" }) { model ->
                    ModelRow(model, current = model.key == catalog.currentKey, onClick = { onPickModel(model) })
                }
            }
            if (matches.isEmpty() && roles.isEmpty()) {
                item(key = "__empty") {
                    Text(
                        if (catalog.models.isEmpty()) "The computer reported no available models." else "No model matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

/** Pause after the last keystroke before sending a model search to the host. */
private const val ModelSearchDebounceMs = 200L

@Composable
private fun ModelSectionLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ModelRow(model: ModelInfo, current: Boolean, onClick: () -> Unit, role: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (role != null) SourceTag(role)
                Text(
                    model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.scoped && role == null) {
                    Icon(Icons.Outlined.Star, contentDescription = "Enabled", modifier = Modifier.size(14.dp), tint = scheme.tertiary)
                }
            }
            val facts = listOfNotNull(
                model.key,
                model.contextWindow?.let { "${formatTokens(it)} ctx" },
                if (model.reasoning) "thinking" else null,
            ).joinToString(" · ")
            Text(facts, style = OmsStyle.monoSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Icon(Icons.Filled.Check, contentDescription = "Current model", tint = scheme.primary)
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
    )
}
