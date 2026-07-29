plugins {
    `java-library`
}

base {
    archivesName.set("ysm-mapping-api")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val embeddedCore by configurations.creating {
    isTransitive = false
}

dependencies {
    api(project(":api-core"))
    compileOnly("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")

    embeddedCore(project(":api-core"))
}

tasks.jar {
    inputs.files(embeddedCore)
    from(embeddedCore.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
