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
        maven { url = java.net.URI("https://api.xposed.info/") }
    }
}

rootProject.name = "ColorOSLauncherGlass"
include(":app")

listOf("core", "shader", "squircle", "ui", "preference").forEach { component ->
    val module = "coui-$component"
    include(":$module")
    project(":$module").projectDir = file("third_party/coui/$module")
}
