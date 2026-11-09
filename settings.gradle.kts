pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") { content { includeGroup("de.robv.android.xposed") } }
    }
}
rootProject.name = "HideHUN"
include(":app")
