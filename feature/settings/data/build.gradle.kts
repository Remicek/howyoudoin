plugins {
    alias(libs.plugins.convention.kmp.library)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(projects.core.domain)
                implementation(projects.core.data)
                implementation(projects.feature.settings.domain)
                implementation(libs.koin.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.datastore)
                implementation(libs.datastore.preferences)
            }
        }
    }
}
