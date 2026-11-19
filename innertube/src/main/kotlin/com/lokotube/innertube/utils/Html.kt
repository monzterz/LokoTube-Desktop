package com.lokotube.innertube.utils

private val HTML_ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

/**
 * Decodes the HTML entities YouTube leaves in WEB titles and channel names — emoji arrive as
 * `&#x1f923;`, quotes as `&#39;`, ampersands as `&amp;`. Unknown entities are left untouched.
 */
fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this
    return HTML_ENTITY.replace(this) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)?.toChars() ?: match.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChars() ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

private fun Int.toChars(): String = if (this in 0..0x10FFFF) String(Character.toChars(this)) else ""
