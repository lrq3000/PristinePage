import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Same exact input for every APK variant. Keep build credentials and provider binaries outside the source tree.
tasks.register<Exec>("verifyBundledOcr") {
    workingDir(rootDir)
    commandLine("python", "-B", "validation/providers/prepare_ocr_local_app.py",
        layout.projectDirectory.dir("validation/raw/providers/ocr-app-standard-full").asFile.absolutePath)
}

allprojects {
    group = "app.pristinepage"
    version = "0.1.0-dev"

    // Lock resolved inputs after a successful run; a failed red test is deliberately not accepted as persisted lock evidence.
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
    // Strict locking is the selected resolution mode; Gradle rejects mixing it with the alternative fail-on-dynamic mode. Direct catalog pins, reviewed lock entries and SHA-256 verification constrain the approved graph.
}
