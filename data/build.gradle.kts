import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.pristinepage.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testApplicationId = "app.pristinepage.debug.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val localDevelopmentSigning = signingConfigs.create("localDevelopment") {
        // S-01/I-01 permits only the existing worktree key and this named test package; never generate a global debug key for library instrumentation.
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
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(project(":test-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
