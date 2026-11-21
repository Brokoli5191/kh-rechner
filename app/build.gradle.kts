import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release workflow passes -PappVersionName=<tag without "v">; local builds use the default.
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("1.0.0")

// 1.2.3 -> 10203. Sideloaded updates need a strictly increasing code, so it is derived from the name.
val appVersionCode = appVersionName
    .split('.')
    .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { parts -> (parts.getOrElse(0) { 0 } * 10_000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 } }
    .coerceAtLeast(1)

// Signing material comes from the environment only (see README); nothing is read from the repository.
val releaseKeystore = providers.environmentVariable("KH_KEYSTORE_FILE").orNull

android {
    namespace = "app.brokoli5191.khrechner"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "app.brokoli5191.khrechner"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    androidResources {
        localeFilters += listOf("de", "en")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("KH_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KH_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KH_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // ./gradlew testDebugUnitTest -Pscreenshots rewrites docs/screenshots; otherwise
                // the screenshot test only checks that every screen renders.
                test.systemProperty("roborazzi.test.record", providers.gradleProperty("screenshots").isPresent.toString())
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // The app's copy is lowercase by design, including "cancel".
        disable += "ButtonCase"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.code.scanner)
    implementation(libs.play.services.base)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
