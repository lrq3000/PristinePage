import org.gradle.api.artifacts.dsl.LockMode

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "app.paperlight"
    version = "0.1.0-dev"

    // Lock resolved inputs after a successful run; a failed red test is deliberately not accepted as persisted lock evidence.
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
    // Strict locking is the selected resolution mode; Gradle rejects mixing it with the alternative fail-on-dynamic mode. Direct catalog pins, reviewed lock entries and SHA-256 verification constrain the approved graph.
}
