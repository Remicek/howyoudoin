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

// Static analysis of all Kotlin sources: ./gradlew detekt. Findings that predate it are listed in the baseline
// (./gradlew detektBaseline rewrites it); new ones fail the check.
detekt {
    source.setFrom(subprojects.map { it.file("src") }.filter { it.exists() })
    config.setFrom(file("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
}
