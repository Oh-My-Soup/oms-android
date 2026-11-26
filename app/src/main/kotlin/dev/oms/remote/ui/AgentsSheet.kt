package dev.oms.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.AgentCommand
import dev.oms.remote.protocol.api.AgentInfo

private val StatusOrder = mapOf("running" to 0, "idle" to 1, "parked" to 2, "aborted" to 3)

@Composable
internal fun agentStatusColor(status: String): Color = when (status) {
    "running" -> OmsStyle.status.live
    "idle" -> MaterialTheme.colorScheme.primary
    "aborted" -> MaterialTheme.colorScheme.error
    else -> OmsStyle.status.parked
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentsSheet(
    agents: List<AgentInfo>,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    onCommand: (agentId: String, command: AgentCommand, text: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AgentsList(agents, controlsEnabled, onCommand)
    }
}

/** Sheet body; separate from [AgentsSheet] so it can be previewed. */
@Composable
internal fun AgentsList(
    agents: List<AgentInfo>,
    controlsEnabled: Boolean,
    onCommand: (agentId: String, command: AgentCommand, text: String?) -> Unit,
) {
    val sorted = remember(agents) {
        agents.sortedWith(
            compareBy<AgentInfo> { if (it.kind == "main") 0 else 1 }.thenBy { StatusOrder[it.status] ?: 9 },
        )
    }
    val running = agents.count { it.status == "running" }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "__header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    text = "$running running · ${agents.size} total",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sorted.isEmpty()) {
            item(key = "__empty") {
                Text(
                    text = "No agents reported yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(sorted, key = { it.id }) { agent ->
            AgentCard(rememberEqual(agent), controlsEnabled, onCommand)
        }
    }
}

@Composable
private fun AgentCard(
    agent: AgentInfo,
    controlsEnabled: Boolean,
    onCommand: (agentId: String, command: AgentCommand, text: String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = agentStatusColor(agent.status)
    val progress = agent.progress
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                        .semantics { contentDescription = "Status ${agent.status}" },
                )
                Text(
                    text = agent.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                KindChip(agent.kind)
                Text(agent.status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            val task = progress?.task
            if (!task.isNullOrBlank()) {
                Text(
                    text = task,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val currentTool = progress?.currentTool
            val lastIntent = progress?.lastIntent
            if (agent.status == "running" && !currentTool.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = toolIcon(currentTool),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(currentTool, style = OmsStyle.monoSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    if (!lastIntent.isNullOrBlank()) {
                        Text(
                            text = lastIntent,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (!lastIntent.isNullOrBlank()) {
                Text(
                    text = lastIntent,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val stats = if (progress != null) {
                listOf(
                    pluralize(progress.toolCount, "tool"),
                    "${formatTokens(progress.tokens)} tokens",
                    formatCost(progress.cost),
                    formatDuration(progress.durationMs),
                ).joinToString(" · ")
            } else {
                agent.lastActivityMs?.let { "active ${relativeTime(it)}" }
            }
            if (stats != null) {
                Text(stats, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            val recent = progress?.recentOutput.orEmpty().takeLast(2).filter { it.isNotBlank() }
            if (recent.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        recent.forEach { line ->
                            Text(
                                text = cleanTerminalText(line),
                                style = OmsStyle.monoSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (agent.kind == "sub") {
                AgentChatField(agent.id, controlsEnabled) { text -> onCommand(agent.id, AgentCommand.Chat, text) }
                val canKill = agent.status == "running" || agent.status == "idle"
                val canRevive = agent.status == "parked" || agent.status == "aborted"
                if (canKill || canRevive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        if (canKill) {
                            OutlinedButton(
                                onClick = { onCommand(agent.id, AgentCommand.Kill, null) },
                                enabled = controlsEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                                border = BorderStroke(1.dp, if (controlsEnabled) scheme.error else scheme.outlineVariant),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Kill")
                            }
                        }
                        if (canRevive) {
                            FilledTonalButton(
                                onClick = { onCommand(agent.id, AgentCommand.Revive, null) },
                                enabled = controlsEnabled,
                            ) {
                                Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Revive")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChip(kind: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (kind == "main") scheme.primaryContainer else scheme.secondaryContainer,
        contentColor = if (kind == "main") scheme.onPrimaryContainer else scheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(kind, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AgentChatField(agentId: String, enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable(agentId) { mutableStateOf("") }
    val send = {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Message this agent", style = MaterialTheme.typography.bodyMedium) },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = send, enabled = enabled && text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to agent")
            }
        },
    )
}
