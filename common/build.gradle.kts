plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(project(":api"))
    implementation(project(":analysis-core"))
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    compileOnly("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation("org.ow2.asm:asm:9.7.1")
    testImplementation("org.ow2.asm:asm-tree:9.7.1")
    testImplementation("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
