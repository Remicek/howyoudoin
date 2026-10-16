plugins {
    alias(libs.plugins.convention.cmp.feature)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.core.domain)
                implementation(projects.feature.chat.domain)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.activity.compose)
            }
        }
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}
