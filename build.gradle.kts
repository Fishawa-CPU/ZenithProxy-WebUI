plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
    id("io.freefair.lombok") version "9.5.0"
}

group = "com.zenith.webui"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    compileOnly(files("libs/ZenithProxy.jar"))
    implementation("io.netty:netty-all:4.1.108.Final")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("ZenithProxy-WebUI")
    archiveVersion.set("1.0.0")
    manifest {
        attributes(
            "Implementation-Title" to "ZenithProxy WebUI Plugin",
            "Implementation-Version" to project.version
        )
    }
}
