package dev.brahmkshatriya.echo.extension

import java.net.URLDecoder

internal object SoundCloudSession {
    fun normalize(raw: String?): String? {
        var value = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (value.contains('%')) value = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        value = value.removeSurrounding("\"")
        value = value.replace(Regex("^(?:OAuth|Bearer)\\s+", RegexOption.IGNORE_CASE), "").trim()
        if (value.equals("OAuth", true) || value.equals("Bearer", true)) return null
        return value.takeIf { it.isNotBlank() && it.none { c -> c.isWhitespace() || c in ";{}\"" || c.isISOControl() } }
    }
    fun cookieToken(cookie: String): String? {
        val values = cookie.split(';').mapNotNull {
            val entry = it.trim().split('=', limit = 2)
            if (entry.size == 2) entry[0] to entry[1] else null
        }.toMap()
        return normalize(values["oauth_token"]) ?: normalize(values["auth_token"])
    }
    fun inputToken(raw: String): String? =
        if (raw.trim().startsWith("oauth_token=") || raw.trim().startsWith("auth_token=")) cookieToken(raw) else normalize(raw)
    fun clientId(raw: String?) = raw?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,128}")) }
}
