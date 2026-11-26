package dev.oms.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.oms.remote.protocol.api.AssistantBlock
import dev.oms.remote.protocol.api.MarkerKind
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.TranscriptItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val OlderKey = "__older__"
private const val WorkingKey = "__working__"

private fun contentTypeOf(item: TranscriptItem): String = when (item) {
    is TranscriptItem.User -> "user"
    is TranscriptItem.Assistant -> "assistant"
    is TranscriptItem.Tool -> "tool"
    is TranscriptItem.Custom -> "custom"
    is TranscriptItem.Marker -> "marker"
}

private data class EndProbe(val count: Int, val lastIndex: Int, val lastEnd: Int, val viewportEnd: Int)

/** Scrolls so the end of the last item sits at the bottom of the viewport. */
private suspend fun LazyListState.snapToEnd() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != count - 1) scrollToItem(count - 1)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != count - 1) return
    val overflow = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
    if (overflow > 0) scrollBy(overflow.toFloat())
}

/**
 * The transcript. Sticks to the bottom while the user is at the bottom; once they scroll up it
 * stays put and offers a "jump to latest" button.
 */
@Composable
internal fun Transcript(
    items: List<TranscriptItem>,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    showWorking: Boolean,
    aborting: Boolean,
    phase: Phase,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offers "Rewind to here" on each prompt; null when this guest cannot rewind the host. */
    onRewind: ((TranscriptItem.User) -> Unit)? = null,
) {
    val extraTop = if (hasOlder) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (items.size + extraTop - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }

    // Only user gestures (drag/fling) dispatch nested scroll, so programmatic scrolls never flip [follow].
    val followConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y > 0.5f) {
                    follow = false
                } else if ((consumed.y < 0f || available.y < 0f) && !listState.canScrollForward) {
                    follow = true
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollForward) follow = true
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            EndProbe(
                count = info.totalItemsCount,
                lastIndex = last?.index ?: -1,
                lastEnd = (last?.offset ?: 0) + (last?.size ?: 0),
                viewportEnd = info.viewportEndOffset,
            )
        }.collect {
            if (!follow) return@collect
            try {
                listState.snapToEnd()
            } catch (e: CancellationException) {
                // A user gesture interrupted the scroll (MutatorMutex); keep collecting unless we were cancelled.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(followConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (hasOlder) {
                item(key = OlderKey, contentType = "older") { LoadOlderRow(loadingOlder, onLoadOlder) }
            }
            items(items, key = { it.key }, contentType = { contentTypeOf(it) }) { item ->
                TranscriptRow(rememberEqual(item), onRewind)
            }
            if (showWorking) {
                item(key = WorkingKey, contentType = "working") { WorkingRow(aborting) }
            }
        }

        if (items.isEmpty() && !hasOlder) {
            EmptyTranscript(phase, Modifier.align(Alignment.Center).padding(32.dp))
        }

        val showJump by remember { derivedStateOf { !follow && listState.canScrollForward } }
        AnimatedVisibility(
            visible = showJump,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        val count = listState.layoutInfo.totalItemsCount
                        if (count > 0) listState.animateScrollToItem(count - 1)
                        follow = true
                        listState.snapToEnd()
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = "Jump to latest")
            }
        }
    }
}

@Composable
private fun TranscriptRow(item: TranscriptItem, onRewind: ((TranscriptItem.User) -> Unit)?) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item, onRewind?.let { rewind -> { rewind(item) } })
        is TranscriptItem.Assistant -> AssistantMessage(item)
        is TranscriptItem.Tool -> ToolCard(item.call)
        is TranscriptItem.Custom -> CustomRow(item)
        is TranscriptItem.Marker -> MarkerRow(item)
    }
}

@Composable
private fun UserBubble(item: TranscriptItem.User, onRewind: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val from = item.from
        if (from != null || onRewind != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRewind != null) {
                    // An icon, not a long press: the bubble's text is selectable.
                    IconButton(onClick = onRewind, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = "Rewind to here",
                            modifier = Modifier.size(18.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (from != null) GuestBadge(from)
            }
        }
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (item.text.isNotBlank()) {
                    SelectionContainer { Text(item.text, style = OmsStyle.body) }
                }
                if (item.imageCount > 0) ImagesLabel(item.imageCount)
            }
        }
    }
}

@Composable
private fun GuestBadge(from: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(
                text = from,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(item: TranscriptItem.Assistant) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val blocks = item.blocks
        blocks.forEachIndexed { index, block ->
            when (block) {
                is AssistantBlock.Text -> if (block.markdown.isNotBlank()) {
                    key("text-$index") { MarkdownText(block.markdown) }
                }
                is AssistantBlock.Thinking -> key("thinking-$index") {
                    ThinkingBlock(block, live = item.streaming && index == blocks.lastIndex)
                }
                is AssistantBlock.Tool -> key(block.call.toolCallId) { ToolCard(block.call) }
            }
        }
        val stop = item.stopReason
        if (stop == "error" || stop == "aborted") StopReasonChip(stop, item.errorMessage)
        if (item.streaming && blocks.lastOrNull() !is AssistantBlock.Tool) StreamingCursor()
    }
}

/** Assistant Markdown, parsed synchronously on first show so list items don't jump. */
@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val state = rememberMarkdownState(content = markdown, retainState = true, immediate = true)
    val body = OmsStyle.body
    val colors = markdownColor(
        text = scheme.onSurface,
        codeBackground = scheme.surfaceContainerHighest,
        inlineCodeBackground = scheme.surfaceContainerHigh,
        dividerColor = scheme.outlineVariant,
        tableBackground = scheme.surfaceContainerLow,
    )
    val typography = markdownTypography(
        h1 = type.titleLarge,
        h2 = type.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
        h3 = type.titleMedium,
        h4 = type.titleSmall,
        h5 = type.titleSmall,
        h6 = type.titleSmall,
        text = body,
        code = OmsStyle.mono,
        inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp),
        quote = body.copy(fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )
    SelectionContainer(modifier) {
        Markdown(
            markdownState = state,
            colors = colors,
            typography = typography,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThinkingBlock(block: AssistantBlock.Thinking, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val canExpand = !block.redacted && block.text.isNotBlank()
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "thinking-chevron")
    Column {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (canExpand) {
                            Modifier.clickable(onClickLabel = if (expanded) "Collapse thinking" else "Expand thinking") {
                                expanded = !expanded
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 10.dp, end = if (canExpand) 6.dp else 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = when {
                        block.redacted -> "thinking · redacted"
                        live -> "Thinking…"
                        else -> "Thinking"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (canExpand) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).rotate(rotation),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = expanded && canExpand,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val bar = scheme.outlineVariant
            SelectionContainer {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 6.dp)
                        .drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }
                        .padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StopReasonChip(reason: String, message: String?) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (reason == "aborted") Icons.Outlined.Cancel else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Text(if (reason == "aborted") "Aborted" else "Error", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.weight(1f).padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha = transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "cursor-alpha",
    )
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .semantics { contentDescription = "Agent is writing" }
            .size(width = 8.dp, height = 18.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun CustomRow(item: TranscriptItem.Custom) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
        ) {
            Text(
                text = item.customType,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (item.text.isNotBlank()) {
            SelectionContainer {
                Text(item.text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private fun markerIcon(kind: MarkerKind): ImageVector = when (kind) {
    MarkerKind.Compaction -> Icons.Outlined.Compress
    MarkerKind.BranchSummary -> Icons.AutoMirrored.Outlined.CallSplit
    MarkerKind.ModelChange -> Icons.Outlined.SwapHoriz
    MarkerKind.ThinkingChange -> Icons.Outlined.Psychology
}

@Composable
private fun MarkerRow(item: TranscriptItem.Marker) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = scheme.outlineVariant)
        Row(
            modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = markerIcon(item.kind),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = item.text,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(Modifier.weight(1f), color = scheme.outlineVariant)
    }
}

@Composable
private fun WorkingRow(aborting: Boolean) {
    val transition = rememberInfiniteTransition(label = "working")
    val shift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "working-shift",
    )
    val highlight = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        // The shimmer is drawn over the text in the draw phase, so the row never recomposes per frame.
        Text(
            text = if (aborting) "Stopping…" else "Working…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val band = size.width * 0.6f
                    val x = -band + shift.value * (size.width + band)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            startX = x,
                            endX = x + band,
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}

@Composable
private fun LoadOlderRow(loading: Boolean, onLoadOlder: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(8.dp)
                    .size(22.dp)
                    .semantics { contentDescription = "Loading older messages" },
                strokeWidth = 2.dp,
            )
        } else {
            TextButton(onClick = onLoadOlder) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Load older")
            }
        }
    }
}

@Composable
private fun EmptyTranscript(phase: Phase, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            Phase.Connecting, Phase.Reconnecting -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                Text(
                    text = if (phase == Phase.Connecting) "Joining session…" else "Reconnecting…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            Phase.Live -> {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(scheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = scheme.onSecondaryContainer,
                    )
                }
                Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Send a prompt to get the agent started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Phase.Ended, Phase.Failed -> {
                Icon(
                    imageVector = Icons.Outlined.LinkOff,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = "This session is no longer available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
