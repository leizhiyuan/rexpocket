pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}
rootProject.name = "rexpocket"
include(":core", ":app")
// The Netbird add-on: a separate app that runs a Netbird node for RexPocket, and the interface
// both sides of its binding share. See docs/design/tailnet-backends.md.
include(":netbirdaddon", ":netbirdaddon-api")
