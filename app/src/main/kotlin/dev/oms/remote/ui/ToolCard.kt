package dev.oms.remote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.ToolCallView
import dev.oms.remote.protocol.api.ToolStatus

private const val CollapsedLines = 12
private const val ExpandedLines = 400
private const val CollapsedLineChars = 400
private const val ExpandedLineChars = 4_000
private const val ExpandedMaxChars = 80_000

/** Collapsible card for one tool call (used inside assistant messages and for standalone tool rows). */
@Composable
internal fun ToolCard(call: ToolCallView, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(call.toolCallId) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val border = if (call.status == ToolStatus.Error) scheme.error.copy(alpha = 0.45f) else scheme.outlineVariant
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, border),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "Collapse tool call" else "Expand tool call") {
                        expanded = !expanded
                    }
                    .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolIconBadge(call.name)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = call.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1,
                        )
                        val summary = call.summary.lineSequence().firstOrNull().orEmpty()
                        if (summary.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = summary,
                                style = OmsStyle.monoSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    val intent = call.intent
                    if (!intent.isNullOrBlank()) {
                        Text(
                            text = intent,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ToolStatusIndicator(call.status)
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(rotation),
                )
            }
            if (expanded) ToolDetails(call)
        }
    }
}

@Composable
private fun ToolIconBadge(name: String) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = toolIcon(name),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun ToolStatusIndicator(status: ToolStatus, size: Dp = 18.dp) {
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(
            modifier = Modifier.size(size).semantics { contentDescription = "Running" },
            strokeWidth = 2.dp,
        )
        ToolStatus.Done -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Done",
            tint = OmsStyle.status.live,
            modifier = Modifier.size(size),
        )
        ToolStatus.Error -> Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(size),
        )
        ToolStatus.Unknown -> Unit
    }
}

@Composable
private fun ToolDetails(call: ToolCallView) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = scheme.outlineVariant)
        val args = call.argsJson.trim()
        if (args.isNotEmpty() && args != "{}") {
            SectionLabel("Arguments")
            MonoBlock(args)
        }
        val output = call.output
        when {
            call.resultMissing -> Text(
                text = "Result not on this page",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant,
            )
            output != null -> {
                SectionLabel(
                    when (call.status) {
                        ToolStatus.Error -> "Error"
                        ToolStatus.Running -> "Output (live)"
                        else -> "Output"
                    },
                )
                if (output.isBlank()) {
                    Text(
                        text = "(no output)",
                        style = OmsStyle.mono,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    MonoBlock(output, isError = call.status == ToolStatus.Error)
                }
            }
            call.status == ToolStatus.Running -> Text(
                text = "Waiting for output…",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (call.imageCount > 0) ImagesLabel(call.imageCount)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ImagesLabel(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(pluralize(count, "image"), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Monospace text block that scrolls horizontally (no soft wrap). Shows the first
 * [CollapsedLines] lines with a "Show more" toggle; very large text is clipped.
 */
@Composable
internal fun MonoBlock(text: String, isError: Boolean = false, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val cleaned = remember(text) { cleanTerminalText(text.trimEnd()) }
    val collapsed = remember(cleaned) { clipText(cleaned, CollapsedLines, CollapsedLineChars, Int.MAX_VALUE) }
    val shown = if (showAll) {
        remember(cleaned) { clipText(cleaned, ExpandedLines, ExpandedLineChars, ExpandedMaxChars) }
    } else {
        collapsed
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHighest,
    ) {
        Column {
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = shown.text,
                        style = OmsStyle.mono,
                        color = if (isError) scheme.error else scheme.onSurface,
                        softWrap = false,
                    )
                }
            }
            if (showAll && shown.hiddenLines > 0) {
                Text(
                    text = "… ${pluralize(shown.hiddenLines, "more line")} not shown",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            if (collapsed.truncated) {
                TextButton(
                    onClick = { showAll = !showAll },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(
                        text = when {
                            showAll -> "Show less"
                            collapsed.hiddenLines > 0 -> "Show more (${pluralize(collapsed.hiddenLines, "line")})"
                            else -> "Show more"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Monospace box with its own vertical and horizontal scroll, capped at [maxHeight] (approval bodies). */
@Composable
internal fun ScrollableMonoBox(text: String, maxHeight: Dp, modifier: Modifier = Modifier) {
    val cleaned = remember(text) {
        clipText(cleanTerminalText(text), ExpandedLines, ExpandedLineChars, ExpandedMaxChars).text
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SelectionContainer {
            Box(
                Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = cleaned,
                    style = OmsStyle.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
        }
    }
}
