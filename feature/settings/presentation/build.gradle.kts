plugins {
    alias(libs.plugins.convention.cmp.feature)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.core.domain)
                implementation(projects.feature.settings.domain)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.components.resources)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
