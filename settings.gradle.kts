pluginManagement {

    repositories {

        google()

        mavenCentral()

        gradlePluginPortal()
    }
}

dependencyResolutionManagement {

    repositoriesMode.set(
        RepositoriesMode.PREFER_SETTINGS
    )

    repositories {

        google()

        mavenCentral()
    }
}

rootProject.name = "Salaat_Android_Dashboard"

include(":app")
