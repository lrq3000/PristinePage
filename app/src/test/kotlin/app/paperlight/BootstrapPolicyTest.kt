package app.paperlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the runtime bootstrap boundary without an Android Context; reflection permits an intentional assertion failure before the policy implementation exists. Manifest and packaged-permission checks are separate gates. */
class BootstrapPolicyTest {
    @Test
    fun api26IsTheMinimumAndOlderPlatformsAreRejected() {
        val policy = policyType()
        assertEquals(26, policy.getField("minApi").getInt(null))
        val supportsApi = policy.getMethod("supportsApi", Int::class.javaPrimitiveType!!)
        val instance = policy.getField("INSTANCE").get(null)
        assertEquals(false, supportsApi.invoke(instance, 25))
        assertEquals(true, supportsApi.invoke(instance, 26))
        assertEquals(true, supportsApi.invoke(instance, 36))
    }

    @Test
    fun noUnapprovedModelCanBeEnabledAtBootstrap() {
        assertTrue(policySet("getReleaseProviderAllowlist").isEmpty())
        val policy = policyType()
        val permitsProvider = policy.getMethod("permitsProvider", String::class.java)
        assertEquals(false, permitsProvider.invoke(policy.getField("INSTANCE").get(null), "unapproved-model"))
    }

    @Test
    fun noProductionNetworkDestinationIsConfigured() {
        assertTrue(policySet("getProductionNetworkEndpoints").isEmpty())
    }

    @Test
    fun aFreshBootstrapDoesNotRequireAModelAsset() {
        val policy = policyType()
        val requiresModel = policy.getMethod("getRequiresModelForLaunch")
        assertFalse(requiresModel.invoke(policy.getField("INSTANCE").get(null)) as Boolean)
    }

    private fun policySet(getter: String): Set<*> {
        val policy = policyType()
        return policy.getMethod(getter).invoke(policy.getField("INSTANCE").get(null)) as Set<*>
    }

    private fun policyType(): Class<*> = try {
        Class.forName("app.paperlight.BuildPolicy")
    } catch (missing: ClassNotFoundException) {
        throw AssertionError("Bootstrap policy is absent: minimum platform, provider and network capabilities are not implemented.", missing)
    }
}
