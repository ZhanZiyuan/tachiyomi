plugins {
    id("com.android.library")
    kotlin("android")
    alias(kotlinx.plugins.compose.compiler)
}

android {
    namespace = "tachiyomi.presentation.widget"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":presentation-core"))
    api(project(":i18n"))

    implementation(compose.glance)
    lintChecks(compose.lintchecks)

    implementation(kotlinx.immutables)

    implementation(platform(libs.coil.bom))
    implementation(libs.coil.core)

    api(libs.injekt.core)
}
