import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.pristinepage.image"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testApplicationId = "app.pristinepage.debug.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters += "arm64-v8a"
    }
    val localDevelopmentSigning = signingConfigs.create("localDevelopment") {
        // Reuse the exact S-01/I-01 key/test package, like data's self-target test APK. Never generate a global key; only one module's test APK is installed at a time on the owned P-04 instance.
        storeFile = rootProject.file(".local-signing/debug.keystore")
        storeType = "PKCS12"
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    buildTypes {
        debug { signingConfig = localDevelopmentSigning }
        release { signingConfig = null }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = false
}
kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.exifinterface)
    testImplementation(project(":test-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // The production data dependency owns Room; expose its already-approved runtime API only to real cross-module instrumentation fixtures.
    androidTestImplementation(libs.room.runtime)
}
