plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.zenith.webui"
version = "1.4.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    compileOnly(files("libs/ZenithProxy.jar"))
    implementation("io.netty:netty-all:4.1.108.Final")
}

// Sources contain Chinese log strings — never rely on the platform default charset.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("ZenithProxy-WebUI")
    archiveVersion.set(project.version.toString())
    manifest {
        attributes(
            "Implementation-Title" to "ZenithProxy WebUI Plugin",
            "Implementation-Version" to project.version
        )
    }
}

// `./gradlew build` should yield the distributable fat JAR, not just the thin one.
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
