pluginManagement {
    // Версии объявлены здесь (а не в build.gradle.kts), чтобы модуль можно было
    // подключать в чужой settings.gradle.kts с уже заданными версиями плагинов.
    plugins {
        id("com.android.library") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.10"
    }
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
    }
}
rootProject.name = "ambient-stage-player"
