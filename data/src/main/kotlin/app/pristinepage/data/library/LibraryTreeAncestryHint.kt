package app.pristinepage.data.library

import java.io.IOException

/** For the trusted local Android provider only. These counts are not paths, grant identities or evidence of separation. */
internal object LibraryTreeAncestryHint {
    fun parentHops(left: String, right: String): Pair<Int, Int> {
        val a = components(left)
        val b = components(right)
        var common = 0
        while (common < minOf(a.size, b.size) && a[common] == b[common]) common++
        if (common == a.size || common == b.size) unavailable()
        return (a.size - common) to (b.size - common)
    }

    private fun components(id: String): List<String> {
        if (id.length > 2_048) unavailable()
        val path = id.substringAfter(':', "").split('/')
        if (path.any { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) }) unavailable()
        val components = when (id.substringBefore(':')) {
            "home" -> listOf("Documents") + path
            "primary" -> path
            else -> unavailable()
        }
        if (components.size !in 1 until LibraryRootSeparation.MAX_CHAIN) unavailable()
        return components
    }

    private fun unavailable(): Nothing = throw IOException("The selected library folders have no usable common-ancestry hint")
}
