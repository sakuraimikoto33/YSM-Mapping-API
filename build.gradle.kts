import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
}

group = "net.okitsu.ysmmapping"
version = providers.gradleProperty("modVersion").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    pluginManager.withPlugin("java") {
        dependencies.add(
            "testRuntimeOnly",
            "org.junit.platform:junit-platform-launcher:1.11.4"
        )
    }

    tasks.withType<Jar>().configureEach {
        includeEmptyDirs = false
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

val verifyDistributions = tasks.register("verifyDistributions") {
    group = "verification"
    description = "Verifies the Fabric and Forge prerequisite mod distributions."
    dependsOn(":fabric:remapJar", ":forge:reobfJar")
    doLast {
        val jars = listOf(
            project(":fabric").tasks.named("remapJar").get().outputs.files.singleFile,
            project(":forge").tasks.named("jar").get().outputs.files.singleFile
        )
        for (jar in jars) {
            ZipFile(jar).use { zip ->
                val names = zip.entries().asSequence()
                    .map { it.name }
                    .toList()
                val packMetadata = zip.getEntry("pack.mcmeta")
                    ?: error("pack.mcmeta missing from ${jar.name}")
                val packText = zip.getInputStream(packMetadata).bufferedReader().use {
                    it.readText()
                }
                @Suppress("UNCHECKED_CAST")
                val pack = (JsonSlurper().parseText(packText) as Map<String, Any?>)["pack"]
                    as? Map<String, Any?>
                    ?: error("pack.mcmeta lacks a pack object in ${jar.name}")
                require((pack["pack_format"] as? Number)?.toInt() == 15) {
                    "pack.mcmeta must use resource pack format 15 in ${jar.name}"
                }
                val requiredClasses = listOf(
                    "net/okitsu/ysmmapping/api/MappingTarget.class",
                    "net/okitsu/ysmmapping/api/MappingEntry.class",
                    "net/okitsu/ysmmapping/internal/analysis/AnalysisProfile.class",
                    "net/okitsu/ysmmapping/internal/cache/MappingEngine.class"
                )

                for (required in requiredClasses) {
                    require(names.count { it == required } == 1) {
                        "$required must occur exactly once in ${jar.name}"
                    }
                }

                require(names.none { it.startsWith("com/elfmcys/yesstevemodel/") }) {
                    "Proprietary YSM classes found in ${jar.name}"
                }
                require(names.any { it.endsWith("ysm_mapping_api.mixins.json") }) {
                    "Bootstrap mixin configuration missing from ${jar.name}"
                }
                require(names.none { it.startsWith("ysm_mapping_api/reference/") }) {
                    "Version-specific YSM reference data found in ${jar.name}"
                }
                require(names.none { it.contains("neoforge", ignoreCase = true) }) {
                    "NeoForge content found in ${jar.name}"
                }
                for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                    val header = zip.getInputStream(entry).use { it.readNBytes(8) }
                    require(header.size == 8) { "Truncated class in ${jar.name}: ${entry.name}" }
                    val major = ((header[6].toInt() and 0xff) shl 8) or
                        (header[7].toInt() and 0xff)
                    require(major <= 61) {
                        "Java $major classfile exceeds Java 17 in ${jar.name}: ${entry.name}"
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(verifyDistributions)
}
