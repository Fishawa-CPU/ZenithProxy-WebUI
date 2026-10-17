plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.zenith.webui"
version = "1.4.0"

// ZenithProxy 由 maven.2b2t.vc 提供（仓库已在 settings.gradle.kts 配置）。
// 格式为 <proxy 版本>+<MC 版本>，例如 3.7.3+1.21.4；也可用 1.21.4-SNAPSHOT 跟随快照。
val zenithProxyVersion = "3.7.3+1.21.4"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    // 主程序在运行时提供，只参与编译，不能打进插件 JAR
    compileOnly("com.zenith:ZenithProxy:$zenithProxyVersion")
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
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "wxh1024",
            "License" to "AGPL-3.0-or-later"
        )
    }
}

// `./gradlew build` should yield the distributable fat JAR, not just the thin one.
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// ---------------------------------------------------------------------------
// 独立分发（standalone / Windows exe）
//
// 插件平时以 compileOnly 引用 ZenithProxy，由宿主提供运行时。独立分发则相反：
// 需要把 ZenithProxy 本体和它的全部传递依赖一起收进发行目录，再由 jpackage
// 打成一个自带 JRE、双击即用的 Windows 应用。
// ---------------------------------------------------------------------------

val zenithRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "ZenithProxy 完整运行时依赖，仅用于独立分发"
}

dependencies {
    zenithRuntime("com.zenith:ZenithProxy:$zenithProxyVersion")
}

val distDir = layout.buildDirectory.dir("dist")

// ZenithProxy 本体 + 全部传递依赖 → build/dist/lib
val collectRuntime by tasks.registering(Copy::class) {
    group = "distribution"
    description = "收集 ZenithProxy 及其传递依赖到 build/dist/lib"
    from(zenithRuntime) {
        // 主 JAR 去掉版本号，jpackage --main-jar 才能固定引用
        rename { name ->
            if (name.startsWith("ZenithProxy-") && name.endsWith(".jar")) "zenithproxy.jar" else name
        }
    }
    into(distDir.map { it.dir("lib") })
}

// WebUI 插件 JAR → build/dist/plugins
// ZenithProxy 自带 netty 4.2.x，插件里再带一份 4.1.x 会在同一个 classloader
// 里撞车，所以分发版剔除 netty，统一用宿主提供的版本。
// 注意：不能用 Copy + exclude —— Copy 复制的是 jar 文件本身，exclude 匹配不到
// jar 内部条目；必须先用 zipTree 展开再重新打包。
val collectPlugin by tasks.registering(Zip::class) {
    group = "distribution"
    description = "重新打包 WebUI 插件 JAR 到 build/dist/plugins（剔除 netty）"
    from(zipTree(tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })) {
        exclude("io/netty/**")
        exclude("META-INF/versions/*/io/netty/**")
    }
    archiveFileName.set("webui.jar")
    destinationDirectory.set(distDir.map { it.dir("plugins") })
}

tasks.register("prepareDist") {
    group = "distribution"
    description = "准备独立分发的文件布局（lib/ + plugins/）"
    dependsOn(collectRuntime, collectPlugin)
}
