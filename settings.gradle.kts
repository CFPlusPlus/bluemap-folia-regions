rootProject.name = "BlueMap-Folia-Regions"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugin("shadow", "com.gradleup.shadow").version("8.3.9")
            plugin("paper", "io.papermc.paperweight.userdev").version("2.0.0-beta.19")
            plugin("runpaper", "xyz.jpenilla.run-paper").version("2.3.0")
        }
    }
}
