plugins {
    `java-library`
    id("net.minecraftforge.gradle") version "6.0.54"
}

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val forgeVersion = providers.gradleProperty("forgeVersion").get()
val resourceVersion = rootProject.version.toString()

base {
    archivesName.set("ysm-mapping-api-forge-$minecraftVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

minecraft {
    mappings("official", minecraftVersion)
}

val embeddedCommon by configurations.creating {
    isTransitive = false
}

dependencies {
    minecraft("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")
    implementation(project(":api"))
    implementation(project(":common"))

    embeddedCommon(project(":api"))
    embeddedCommon(project(":analysis-core"))
    embeddedCommon(project(":common"))
}

tasks.jar {
    inputs.files(embeddedCommon)
    from(embeddedCommon.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["MixinConfigs"] = "ysm_mapping_api.mixins.json"
    }
    finalizedBy("reobfJar")
}

tasks.processResources {
    inputs.property("version", resourceVersion)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("forgeVersion", forgeVersion)

    filesMatching("META-INF/mods.toml") {
        expand(
            "version" to resourceVersion,
            "minecraftVersion" to minecraftVersion,
            "forgeVersion" to forgeVersion
        )
    }
}
