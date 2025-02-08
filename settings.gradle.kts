pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)  // Esta línea asegura que se usen estos repositorios
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Jerico"
include(":app")
