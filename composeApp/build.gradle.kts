import java.util.Properties

plugins {
    alias(libs.plugins.convention.cmp.application)
    alias(libs.plugins.aboutlibraries)
}

// Telegram API credentials (my.telegram.org) stay out of git: telegram.apiId / telegram.apiHash in local.properties.
// Without them the app builds without on-device Telegram.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    buildFeatures {
        buildConfig = true
    }
    // Release signing, also from local.properties (never in git):
    //   release.storeFile=/path/to/howyoudoin-release.jks, release.storePassword, release.keyAlias, release.keyPassword
    val releaseStore = localProperties.getProperty("release.storeFile")?.let(::file)
    if (releaseStore != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseStore
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    defaultConfig {
        buildConfigField("long", "TELEGRAM_API_ID", "${localProperties.getProperty("telegram.apiId")?.trim()?.toLongOrNull() ?: 0}L")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${localProperties.getProperty("telegram.apiHash")?.trim().orEmpty()}\"")
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.androidx.biometric)
            implementation(libs.stream.webrtc.android)
            implementation(libs.jetbrains.compose.material.icons.extended)
            // On-device Messenger bridge, built by bridge-mobile/scripts/build-android.sh (not in git)
            implementation(files("libs/hydbridge.aar"))
        }
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.domain)
            implementation(projects.core.designsystem)
            implementation(projects.core.presentation)

            implementation(projects.feature.auth.data)
            implementation(projects.feature.auth.presentation)

            implementation(projects.feature.chat.domain)
            implementation(projects.feature.chat.data)
            implementation(projects.feature.chat.presentation)

            implementation(projects.feature.networks.domain)
            implementation(projects.feature.networks.data)
            implementation(projects.feature.networks.presentation)

            implementation(projects.feature.settings.domain)
            implementation(projects.feature.settings.data)
            implementation(projects.feature.settings.presentation)

            implementation(libs.jetbrains.compose.navigation)
            implementation(libs.bundles.koin.common)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.jetbrains.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.compose)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// The app libraries' licenses, shown in Settings → Open-source licenses. Regenerate after changing dependencies:
//   ./gradlew :composeApp:exportLibraryDefinitions
aboutLibraries {
    export {
        outputFile = rootProject.file("feature/settings/presentation/src/commonMain/composeResources/files/aboutlibraries.json")
        prettyPrint = true
    }
}

// On-device Messenger bridge for iOS: static C libraries from bridge-mobile/scripts/build-ios.sh (not in git),
// linked into the app through the cinterop klib.
kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        val slice = when (konanTarget) {
            org.jetbrains.kotlin.konan.target.KonanTarget.IOS_ARM64 -> "ios-arm64"
            org.jetbrains.kotlin.konan.target.KonanTarget.IOS_SIMULATOR_ARM64 -> "ios-simulator-arm64"
            else -> "ios-simulator-x64"
        }
        val libDir = file("libs/ios/$slice")
        val interop = compilations.getByName("main").cinterops.create("hydbridge") {
            definitionFile.set(file("src/nativeInterop/cinterop/hydbridge.def"))
            compilerOpts("-I$libDir")
            extraOpts("-libraryPath", libDir.absolutePath)
        }
        // Rebuilt libraries must regenerate the bindings
        project.tasks.named(interop.interopProcessingTaskName).configure {
            inputs.files(libDir.resolve("libhydbridge.h"), libDir.resolve("libhydbridge.a")).optional()
        }
    }
}
