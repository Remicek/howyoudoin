plugins {
    alias(libs.plugins.convention.cmp.application)
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
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
