package app.pristinepage

/** Validation-only exact fixture read boundary. The caller supplies independently authenticated canonical fixture URIs; unknown wrapper mappings are refused, never opened to discover their identity. All membership and effective read-only checks complete before the first opener. */
object P06GrantScope {
    @JvmStatic fun read(allowed: List<String>, observed: List<String>, readOnly: (String) -> Boolean, open: (String) -> String): List<String> {
        require(allowed.size == 2 && allowed.distinct().size == 2)
        require(allowed.all { it.matches(Regex("content://media/external/images/media/[1-9][0-9]*")) })
        require(observed.size == 2 && observed.distinct().size == 2 && observed.toSet() == allowed.toSet())
        require(observed.all(readOnly))
        return observed.map(open)
    }
}
