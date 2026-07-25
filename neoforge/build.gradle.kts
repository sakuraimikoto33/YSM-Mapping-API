plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.141"
}
val minecraftVersion = providers.gradleProperty("minecraftVersion").get()

base {
    archivesName.set("ysm-mapping-api-neoforge-$minecraftVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

neoForge {
    version = providers.gradleProperty("neoForgeVersion").get()
    runs {
        create("client") { client() }
    }
    mods {
        create("ysm_mapping_api") { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":common"))
}

val embeddedCommon by configurations.creating {
    isTransitive = false
}

dependencies {
    embeddedCommon(project(":api"))
    embeddedCommon(project(":analysis-core"))
    embeddedCommon(project(":common"))
}

tasks.jar {
    inputs.files(embeddedCommon)
    from(embeddedCommon.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("neoForgeVersion", providers.gradleProperty("neoForgeVersion").get())
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "version" to project.version,
            "minecraftVersion" to minecraftVersion,
            "neoForgeVersion" to providers.gradleProperty("neoForgeVersion").get()
        )
    }
}
