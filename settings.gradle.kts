pluginManagement {
    // Kotlin 2.4 requires D8/R8 9.1.29 or newer; use a published 9.1 patch release.
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.57")
        }
    }
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "Tachiyomi"
include(":app")
include(":i18n")
include(":source-api")
include(":core")
include(":macrobenchmark")
include(":data")
include(":domain")
include(":presentation-widget")
include(":presentation-core")
include(":source-local")
include(":core-metadata")
