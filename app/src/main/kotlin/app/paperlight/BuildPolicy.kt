package app.paperlight

/** Bootstrap capabilities are deliberately independent of an Android Context; asset-specific enablement belongs to the later validated provider registry. */
object BuildPolicy {
    const val minApi: Int = BuildConfig.MIN_API

    val releaseProviderAllowlist: Set<String> = emptySet()
    val productionNetworkEndpoints: Set<String> = emptySet()
    val requiresModelForLaunch: Boolean = false

    fun supportsApi(apiLevel: Int): Boolean = apiLevel >= minApi

    fun permitsProvider(providerId: String): Boolean = providerId in releaseProviderAllowlist
}
