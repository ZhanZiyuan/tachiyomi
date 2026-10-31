package eu.kanade.tachiyomi.extension.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keeps existing JSON repository preferences readable while storing modern indexes verbatim. */
object ExtensionRepoUrl {
    fun normalize(input: String): String? {
        val url = input.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null

        val path = url.pathSegments
        val file = path.lastOrNull() ?: return null
        if (file != "index.pb" && file != "index.min.json") return null

        // GitHub's documented raw link and raw.githubusercontent.com identify the same repository.
        val normalized = url.newBuilder().setPathSegment(path.lastIndex, file).build()
        val canonical = if (url.host == "github.com" && path.size >= 5 && path[2] == "raw") {
            normalized.newBuilder().host("raw.githubusercontent.com").removePathSegment(2).build()
        } else {
            normalized
        }
        return canonical.toString().let {
            if (file == "index.min.json") it.removeSuffix("/index.min.json") else it
        }
    }

    fun indexUrl(stored: String): String = when {
        stored.endsWith("/index.pb") || stored.endsWith("/index.min.json") -> stored
        else -> "${stored.trimEnd('/')}/index.min.json"
    }
}
