package com.appvalence.umay.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** A text to show as a link, and where it goes. */
data class TextLink(val text: String, val url: String)

/**
 * String resource [id], with its `%1$s`, `%2$s`… replaced by [links] as tappable
 * links. The sentence stays one translatable string, so a translation can move
 * the links wherever its grammar puts them. Links inside a Text are separate
 * focusable links for TalkBack.
 */
@Composable
fun linkedStringResource(@StringRes id: Int, vararg links: TextLink): AnnotatedString {
    val markers = links.indices.map { (MARKER_BASE + it).toChar().toString() }
    val formatted = stringResource(id, *markers.toTypedArray())
    val linkColor = MaterialTheme.colorScheme.primary
    val linkList = links.toList()
    return remember(formatted, linkList, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var start = 0
            while (start < formatted.length) {
                val next = formatted.indexOfFirst(start) { it.code in MARKER_BASE until MARKER_BASE + linkList.size }
                if (next == -1) {
                    append(formatted.substring(start))
                    break
                }
                append(formatted.substring(start, next))
                val link = linkList[formatted[next].code - MARKER_BASE]
                withLink(LinkAnnotation.Url(link.url, styles)) { append(link.text) }
                start = next + 1
            }
        }
    }
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return -1
}

// Private Use Area characters: never part of real text, so they can stand in for links.
private const val MARKER_BASE = 0xE000
