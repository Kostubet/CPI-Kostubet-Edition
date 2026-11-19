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
        // Same Maven Central artifacts; avoid a regional Central miss for COUI releases.
        maven {
            name = "COUICentralMirror"
            url = java.net.URI("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeGroup("io.github.suqi8.coui.kmp") }
        }
        mavenCentral()
        maven { url = java.net.URI("https://api.xposed.info/") }
    }
}

rootProject.name = "ColorOSLauncherGlass"
include(":app")
