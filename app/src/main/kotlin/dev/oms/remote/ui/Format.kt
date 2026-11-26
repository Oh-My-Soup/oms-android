package dev.oms.remote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Returns the previously remembered instance while [value] stays equal, so children can skip by identity. */
@Composable
internal fun <T> rememberEqual(value: T): T = remember(value) { value }

private val HomePrefix = Regex("""^(/home/[^/]+|/Users/[^/]+|/root)(?=/|$)""")

/** `~`-relative path, cut to the last two segments when longer than [maxLen]. */
internal fun shortenPath(path: String, maxLen: Int = 30): String {
    val p = HomePrefix.replace(path.trim(), "~")
    if (p.length <= maxLen) return p
    val sep = if (p.contains('/')) '/' else '\\'
    val parts = p.split(sep).filter { it.isNotEmpty() }
    if (parts.size <= 2) return "…" + p.takeLast(maxLen - 1)
    val tail = parts.takeLast(2).joinToString(sep.toString())
    return if (tail.length + 2 > maxLen) "…$sep${parts.last()}" else "…$sep$tail"
}

internal fun formatTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 10_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    n < 1_000_000 -> "${n / 1_000}k"
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
}

internal fun formatCost(cost: Double): String = when {
    cost <= 0.0 -> "\$0"
    cost < 0.01 -> "<\$0.01"
    else -> String.format(Locale.US, "\$%.2f", cost)
}

internal fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

private val DateFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", or a date. */
internal fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - epochMs).coerceAtLeast(0)
    val min = diff / 60_000
    val hours = min / 60
    val days = hours / 24
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        hours < 24 -> "$hours h ago"
        days < 2 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> DateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

internal fun pluralize(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/** Icon for a tool card, chosen by tool name. */
internal fun toolIcon(name: String): ImageVector {
    val n = name.lowercase(Locale.US)
    return when {
        n.contains("web") || n.contains("fetch") || n.contains("browser") || n.contains("url") -> Icons.Outlined.Public
        n == "ask" || n.endsWith("__ask") || n.contains("question") -> Icons.AutoMirrored.Outlined.HelpOutline
        n.contains("todo") || n.contains("checklist") || n.contains("plan") -> Icons.Outlined.Checklist
        n.contains("task") || n.contains("agent") -> Icons.Outlined.AccountTree
        n.contains("bash") || n.contains("shell") || n.contains("exec") || n.contains("terminal") ||
            n.contains("python") || n == "run" || n.contains("command") -> Icons.Outlined.Terminal
        n.contains("edit") || n.contains("write") || n.contains("patch") || n.contains("replace") ||
            n.contains("create") -> Icons.Outlined.EditNote
        n.contains("grep") || n.contains("glob") || n.contains("search") || n.contains("find") ||
            n == "ls" || n.contains("list") -> Icons.AutoMirrored.Outlined.ManageSearch
        n.contains("read") || n.contains("view") || n.contains("cat") || n.contains("file") -> Icons.Outlined.Description
        else -> Icons.Outlined.Build
    }
}

private val AnsiEscape = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007]*\u0007""")

/** Removes ANSI escapes and expands tabs so monospace output lines up. */
internal fun cleanTerminalText(text: String): String {
    val noAnsi = if (text.indexOf('\u001B') >= 0) AnsiEscape.replace(text, "") else text
    return if (noAnsi.indexOf('\t') >= 0) noAnsi.replace("\t", "    ") else noAnsi
}

internal class ClippedText(val text: String, val hiddenLines: Int, val truncated: Boolean)

/** Cuts [text] to [maxLines] lines of at most [maxLineChars] chars and [maxChars] in total. */
internal fun clipText(text: String, maxLines: Int, maxLineChars: Int, maxChars: Int): ClippedText {
    val sb = StringBuilder()
    var truncated = false
    var lineIndex = 0
    var start = 0
    val len = text.length
    while (start <= len) {
        var end = text.indexOf('\n', start)
        if (end < 0) end = len
        if (lineIndex >= maxLines || sb.length >= maxChars) {
            var remaining = 1
            var i = text.indexOf('\n', start)
            while (i >= 0) {
                remaining++
                i = text.indexOf('\n', i + 1)
            }
            return ClippedText(sb.toString().trimEnd('\n'), remaining, true)
        }
        if (lineIndex > 0) sb.append('\n')
        val lineLen = end - start
        if (lineLen > maxLineChars) {
            sb.append(text, start, start + maxLineChars).append(" …")
            truncated = true
        } else {
            sb.append(text, start, end)
        }
        lineIndex++
        if (end == len) break
        start = end + 1
    }
    return ClippedText(sb.toString(), 0, truncated)
}
