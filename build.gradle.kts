plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
    id("io.freefair.lombok") version "9.5.0"
}

group = "com.zenith.webui"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.2b2t.vc/releases")
    maven("https://maven.2b2t.vc/remote")
}

dependencies {
    compileOnly("com.zenith:ZenithProxy:26.1.2-SNAPSHOT")
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
