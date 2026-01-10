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
        mavenCentral() // THIS IS CRITICAL. SceneView lives here.
        maven { url = uri("https://jitpack.io") }// Add this just in case
    }
}

rootProject.name = "Navix"
include(":app")