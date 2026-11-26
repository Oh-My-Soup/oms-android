package dev.oms.remote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.RequestKind
import dev.oms.remote.protocol.api.RequestOption

private val Sentinels = setOf(PendingRequest.OTHER, PendingRequest.CHAT, PendingRequest.NEXT)

/** A question mirrored from the `ask` tool (cancelling it aborts the agent's turn). */
internal val PendingRequest.isAskQuestion: Boolean
    get() = !isApproval && kind == RequestKind.Select &&
        (selectionMarker != null || options.any { it.label in Sentinels })

/**
 * Replaces the composer while a host dialog is pending. Shows the first request of the queue.
 * [waiting] renders a just-answered request disabled while the host sends its follow-up step.
 */
@Composable
internal fun PendingPanel(
    request: PendingRequest,
    total: Int,
    enabled: Boolean,
    waiting: Boolean,
    isStreaming: Boolean,
    onRespond: (PendingRequest, String?) -> Unit,
    onStop: () -> Unit,
    onWriteInstead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmCancel by remember(request.reqId) { mutableStateOf(false) }
    val active = enabled && !waiting
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                .animateContentSize(),
        ) {
            PanelHeader(request, total, waiting)
            Spacer(Modifier.size(10.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    request.isApproval -> ApprovalBody(request, active, onRespond)
                    request.kind == RequestKind.Editor -> EditorBody(request, active, onRespond)
                    else -> SelectBody(request, active, onRespond)
                }
                if (!enabled && !waiting) {
                    Text(
                        text = "Reconnect to answer.",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onWriteInstead, enabled = !waiting) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Write a message")
                }
                Spacer(Modifier.weight(1f))
                if (isStreaming) {
                    TextButton(
                        onClick = onStop,
                        enabled = enabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                    ) { Text("Stop") }
                }
                if (request.kind == RequestKind.Select && !request.isApproval) {
                    TextButton(
                        onClick = {
                            if (request.isAskQuestion) confirmCancel = true else onRespond(request, null)
                        },
                        enabled = active,
                    ) { Text("Cancel") }
                }
            }
        }
    }

    if (confirmCancel) {
        CancelAskDialog(
            hasChat = request.options.any { it.label == PendingRequest.CHAT },
            onDismiss = { confirmCancel = false },
            onChat = {
                confirmCancel = false
                onRespond(request, PendingRequest.CHAT)
            },
            onConfirm = {
                confirmCancel = false
                onRespond(request, null)
            },
        )
    }
}

@Composable
private fun PanelHeader(request: PendingRequest, total: Int, waiting: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (request.isApproval) OmsStyle.status.warn else scheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when {
                request.isApproval -> Icons.Outlined.GppMaybe
                request.kind == RequestKind.Editor -> Icons.Outlined.EditNote
                else -> Icons.AutoMirrored.Outlined.HelpOutline
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = when {
                request.isApproval -> "Approval needed"
                request.kind == RequestKind.Editor -> "Your answer"
                request.isAskQuestion -> "Question"
                else -> "Choose an option"
            },
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (waiting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (total > 1) {
            Surface(
                shape = CircleShape,
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Text(
                    text = "1 of $total",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ApprovalBody(
    request: PendingRequest,
    enabled: Boolean,
    onRespond: (PendingRequest, String?) -> Unit,
) {
    val tool = request.approvalTool.orEmpty().ifBlank { "tool" }
    val detail = request.title.substringAfter('\n', "").trim('\n')
    Text(
        text = buildAnnotatedString {
            append("Allow ")
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)) { append(tool) }
            append("?")
        },
        style = MaterialTheme.typography.titleLarge,
    )
    if (detail.isNotBlank()) ScrollableMonoBox(detail, maxHeight = 220.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        request.options.forEach { option -> ApprovalButton(option.label, request, enabled, onRespond) }
    }
}

@Composable
private fun ApprovalButton(
    label: String,
    request: PendingRequest,
    enabled: Boolean,
    onRespond: (PendingRequest, String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val onClick = { onRespond(request, label) }
    when (label) {
        request.approveOnceLabel -> Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            ButtonLabel(label)
        }
        request.denyLabel -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
            border = BorderStroke(1.dp, if (enabled) scheme.error else scheme.outlineVariant),
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            ButtonLabel(label)
        }
        else -> FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            ButtonLabel(label)
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectBody(
    request: PendingRequest,
    enabled: Boolean,
    onRespond: (PendingRequest, String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val marker = request.selectionMarker
    val markable = if (marker == null) 0 else request.markableCount.coerceIn(0, request.options.size)
    val indexed = request.options.withIndex().toList()
    val isAction = { index: Int, label: String -> label in Sentinels || (marker != null && index >= markable) }
    val rows = indexed.filter { !isAction(it.index, it.value.label) }
    val actions = indexed.filter { isAction(it.index, it.value.label) }

    Text(request.title, style = MaterialTheme.typography.titleMedium)
    if (marker == "checkbox") {
        Text(
            text = "Tap options to toggle them, then continue.",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
    if (rows.isNotEmpty()) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { (index, option) ->
                OptionRow(
                    option = option,
                    marker = if (index < markable) marker else null,
                    checked = index in request.checkedIndices,
                    highlighted = marker != "checkbox" && index == request.initialIndex && request.checkedIndices.isEmpty(),
                    enabled = enabled,
                    onClick = { onRespond(request, option.label) },
                )
            }
        }
    }
    if (actions.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { (_, option) ->
                SentinelButton(option.label, enabled) { onRespond(request, option.label) }
            }
        }
    }
}

@Composable
private fun OptionRow(
    option: RequestOption,
    marker: String?,
    checked: Boolean,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = when (marker) {
        "checkbox" -> Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
        "radio" -> Modifier.selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) scheme.secondaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .then(interaction)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (marker) {
            "radio" -> RadioButton(selected = checked, onClick = null, enabled = enabled)
            "checkbox" -> Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            val description = option.description
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SentinelButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    when (label) {
        PendingRequest.NEXT -> Button(onClick = onClick, enabled = enabled) { Text(label) }
        PendingRequest.OTHER -> OutlinedButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
        PendingRequest.CHAT -> TextButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
        else -> OutlinedButton(onClick = onClick, enabled = enabled) { ButtonLabel(label) }
    }
}

@Composable
private fun EditorBody(
    request: PendingRequest,
    enabled: Boolean,
    onRespond: (PendingRequest, String?) -> Unit,
) {
    var text by rememberSaveable(request.reqId) { mutableStateOf(request.prefill.orEmpty()) }
    Text(request.title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        minLines = 3,
        maxLines = 8,
        textStyle = OmsStyle.body,
        placeholder = { Text("Type your answer") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(onClick = { onRespond(request, null) }, enabled = enabled) { Text("Cancel") }
        Button(onClick = { onRespond(request, text) }, enabled = enabled) { Text("Submit") }
    }
}

@Composable
private fun CancelAskDialog(
    hasChat: Boolean,
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text("Cancel this question?") },
        text = {
            Text(
                if (hasChat) {
                    "Cancelling aborts the agent's current turn. To skip the question and keep the agent " +
                        "running, choose “Chat about this” instead."
                } else {
                    "Cancelling aborts the agent's current turn."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Cancel and abort") }
        },
        dismissButton = {
            Row {
                if (hasChat) TextButton(onClick = onChat) { Text("Chat about this") }
                TextButton(onClick = onDismiss) { Text("Keep") }
            }
        },
    )
}
