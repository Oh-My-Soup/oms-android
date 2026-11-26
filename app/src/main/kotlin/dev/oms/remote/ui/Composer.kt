package dev.oms.remote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.CommandRun
import dev.oms.remote.protocol.api.Phase

/** What the composer warns about when the user chose to type instead of answering a pending request. */
internal enum class PendingNotice { Approval, Question }

@Composable
internal fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    phase: Phase,
    canSend: Boolean,
    isStreaming: Boolean,
    isAborting: Boolean,
    queuedCount: Int,
    pendingNotice: PendingNotice?,
    onBackToRequest: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Completions for a `/` draft; empty when none apply. */
    suggestions: List<SlashSuggestion> = emptyList(),
    onPickSuggestion: (SlashSuggestion) -> Unit = {},
    /** The draft is a `/` command but the host cannot run commands from guests. */
    commandsUnsupported: Boolean = false,
    /** Shows the `/` button that opens the command palette. */
    onOpenCommands: (() -> Unit)? = null,
    /** The draft runs as a command when sent. */
    isCommand: Boolean = false,
    run: CommandRun? = null,
    onDismissRun: () -> Unit = {},
    onCancelRun: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (run != null) {
                CommandRunCard(run, onDismiss = onDismissRun, onCancel = onCancelRun)
            }
            if (pendingNotice != null) {
                PendingWarning(pendingNotice, onBackToRequest)
            }
            if (suggestions.isNotEmpty()) {
                SlashSuggestionList(suggestions, onPickSuggestion)
            } else if (commandsUnsupported) {
                CommandsUnsupportedHint()
            }
            if (isStreaming || queuedCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (isStreaming) "Sending now steers the current turn" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (queuedCount > 0) QueuedBadge(queuedCount)
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    placeholder = {
                        Text(
                            when (phase) {
                                Phase.Connecting -> "Connecting…"
                                Phase.Reconnecting -> "Reconnecting…"
                                Phase.Ended, Phase.Failed -> "Disconnected"
                                Phase.Live -> if (isStreaming) "Steer the agent…" else "Message oms…"
                            },
                        )
                    },
                    textStyle = if (isCommand) OmsStyle.mono else OmsStyle.body,
                    maxLines = 6,
                    leadingIcon = onOpenCommands?.let { open ->
                        {
                            IconButton(onClick = open, enabled = canSend) {
                                Text("/", style = OmsStyle.mono, fontWeight = FontWeight.Bold, color = scheme.primary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = scheme.surfaceContainerHighest,
                        unfocusedContainerColor = scheme.surfaceContainerHighest,
                        disabledContainerColor = scheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                if (isStreaming) {
                    FilledTonalIconButton(
                        onClick = onStop,
                        enabled = canSend && !isAborting,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer,
                        ),
                    ) {
                        if (isAborting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = scheme.onErrorContainer,
                            )
                        } else {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop agent")
                        }
                    }
                }
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend && value.text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    if (isCommand) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Run command")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingWarning(notice: PendingNotice, onBackToRequest: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val approval = notice == PendingNotice.Approval
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (approval) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (approval) scheme.onErrorContainer else scheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (approval) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (approval) {
                    "Sending a message denies the pending approval."
                } else {
                    "The question stays open; your message is delivered after it is answered."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            TextButton(onClick = onBackToRequest) {
                Text(if (approval) "Review" else "Answer")
            }
        }
    }
}

@Composable
internal fun QueuedBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "queued ×$count",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Replaces the composer for view-only links. */
@Composable
internal fun ReadOnlyFooter(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), color = scheme.surfaceContainer) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Visibility, contentDescription = null, tint = scheme.onSurfaceVariant)
            Column {
                Text("View only", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "You can follow along, but this link can't send prompts or answer requests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
