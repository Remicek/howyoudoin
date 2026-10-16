plugins {
    alias(libs.plugins.convention.cmp.library)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.core.domain)
                implementation(libs.bundles.koin.common)
                implementation(libs.jetbrains.lifecycle.compose)
                implementation(compose.components.resources)
                api(libs.kotlinx.datetime)
            }
        }
    }
}
