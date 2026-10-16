plugins {
    alias(libs.plugins.convention.cmp.feature)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.core.domain)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.jetbrains.compose.material.icons.extended)
            }
        }
    }
}
