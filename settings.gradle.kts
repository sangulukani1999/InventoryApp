// In settings.gradle.kts

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        // ❌ REMOVE THE OLD, DEFUNCT REPOSITORY
        // maven { url = uri("https://google.bintray.com/maven") }

        // ✅ ADD THE CORRECT REPOSITORY (redundant but can solve resolution issues)
        // This is often implicitly included with `mavenCentral()`, but explicitly adding it
        // can resolve stubborn "Failed to resolve" errors.
        maven { url = uri("https://repo.maven.apache.org/maven2") }
    }
}

rootProject.name = "zed"
include(":app")
