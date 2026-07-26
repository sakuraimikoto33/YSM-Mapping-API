import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("fabric-loom") version "1.12.7"
}

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()

base {
    archivesName.set("ysm-mapping-api-fabric-$minecraftVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("fabricLoaderVersion").get()}")

    implementation(project(":api"))
    implementation(project(":common"))
}

val apiOutput = project(":api").extensions.getByType<SourceSetContainer>()["main"].output
val apiCoreOutput = project(":api-core").extensions.getByType<SourceSetContainer>()["main"].output
val analysisCoreOutput = project(":analysis-core").extensions
    .getByType<SourceSetContainer>()["main"].output
val commonOutput = project(":common").extensions.getByType<SourceSetContainer>()["main"].output

tasks.jar {
    dependsOn(":api-core:classes", ":analysis-core:classes", ":api:classes", ":common:classes")
    from(apiCoreOutput)
    from(analysisCoreOutput)
    from(apiOutput)
    from(commonOutput)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", minecraftVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftVersion" to minecraftVersion
        )
    }
}
