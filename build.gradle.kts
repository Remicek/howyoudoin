plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.detekt)
}

// Static analysis of all Kotlin sources: ./gradlew detekt. There is no baseline; every finding fails the check.
detekt {
    source.setFrom(subprojects.map { it.file("src") }.filter { it.exists() })
    config.setFrom(file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}
