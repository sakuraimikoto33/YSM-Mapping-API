pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

rootProject.name = "ysm-mapping-api"

include("api-core")
include("analysis-core")
include("mapping-tool")

if (providers.gradleProperty("sharedOnly").orNull != "true") {
    include("api")
    include("common")
    include("fabric")
    include("neoforge")
}
