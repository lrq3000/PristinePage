import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val minimumApi = libs.versions.minSdk.get().toInt()
val bundledOcr = rootProject.layout.projectDirectory.dir("validation/raw/providers/ocr-app-standard-full")

android {
    namespace = "app.pristinepage"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        applicationId = "app.pristinepage"
        minSdk = minimumApi
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "MIN_API", minimumApi.toString())
        ndk.abiFilters += "arm64-v8a"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(bundledOcr.dir("assets"))
    // Pure validation-only scope checks execute identically in host regressions and instrumentation, never in either production or debug application artifacts.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/kotlin")
    val localDevelopmentSigning = signingConfigs.create("localDevelopment") {
        // S-01 approves this disposable local identity only. Never fall back to Android's global debug key or apply this configuration to release.
        storeFile = rootProject.file(".local-signing/debug.keystore")
        storeType = "PKCS12"
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = localDevelopmentSigning
        }
        release {
            isMinifyEnabled = false
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = false
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}
tasks.named("preBuild") { dependsOn(rootProject.tasks.named("verifyBundledOcr")) }
kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// Artifact providers carry producer dependencies: the assertions always see this build's merged debug/release manifests, never stale hard-coded AGP intermediate paths. Source backup XML and its merged references are checked together; packaged resource inspection remains a separate APK gate.
androidComponents {
    onVariants { variant ->
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach {
            inputs.file(mergedManifest).withPropertyName("${variant.name}PolicyManifest")
            doFirst {
                systemProperty("pristinepage.manifest.${variant.name}", mergedManifest.get().asFile.absolutePath)
            }
        }
    }
}
tasks.withType<Test>().configureEach {
    val policyResources = layout.projectDirectory.dir("src/main/res/xml")
    inputs.dir(policyResources).withPropertyName("backupPolicyResources")
    systemProperty("pristinepage.policyResourceDir", policyResources.asFile.absolutePath)
}

dependencies {
    constraints {
        // Activity 1.13.0 requests 1.0.0; retain the already verified 1.0.2 used by the previous app.
        implementation(libs.androidx.navigationevent)
        implementation(libs.androidx.navigationevent.compose)
    }
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":image-android"))
    implementation(files(bundledOcr.file("tesseract4android.aar")))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // SimpleStorage needs this version in the app; standalone module graphs keep the original pin.
    implementation(libs.kotlinx.coroutines.android.app)
    implementation(libs.work.runtime.ktx)
    implementation(libs.simple.storage)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(project(":test-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
