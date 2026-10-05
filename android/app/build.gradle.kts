import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing (TODO-SEC.md S4.1). The key never goes into the repository:
// it is read from environment variables, or from an untracked
// keystore.properties next to settings.gradle.kts (see README.md). Without
// either, the release APK is built unsigned and cannot be installed.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("KIRA_KEYSTORE", "storeFile")

android {
    namespace = "app.kira"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "eu.x7e.kira"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The swtpm machine tests (androidTest app.kira.swtpm) need a host-side
        // machine; they run only through scripts/test-swtpm.sh (-Pkira.swtpm).
        if (!project.hasProperty("kira.swtpm")) {
            testInstrumentationRunnerArguments["notPackage"] = "app.kira.swtpm"
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("KIRA_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KIRA_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KIRA_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release app, so a demo build can never
            // replace (or read the data of) the one holding real machines.
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
            }
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The Go core, built by scripts/build-core.sh from the commit in core.properties.
// kiracore-debug.aar additionally contains kiratest (the demo machine); it
// must never reach a release build, so only the debug variant (which the
// instrumented tests run against) links it.
val coreRelease = files("libs/kiracore.aar")
val coreDebug = files("libs/kiracore-debug.aar")

dependencies {
    releaseImplementation(coreRelease)
    debugImplementation(coreDebug)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
