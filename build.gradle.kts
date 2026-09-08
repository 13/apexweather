plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied to any module (AGP 9 built-in Kotlin), but pins the KGP version on the classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
