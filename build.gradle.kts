plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("net.mamoe.mirai-console") version "2.16.0"
}

group = "kim.hhhhhy"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/nexus/content/groups/public/")
    maven("https://maven.aliyun.com/repository/public")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://jitpack.io")
}

mirai {
    jvmTarget = JavaVersion.VERSION_11
    noTestCore = true
    setupConsoleTestRuntime {
        classpath = classpath.filter {
            !it.nameWithoutExtension.startsWith("mirai-core-jvm")
        }
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("test"))

    implementation(platform("net.mamoe:mirai-bom:2.16.0"))
    compileOnly("net.mamoe:mirai-console-compiler-common")
    testImplementation("net.mamoe:mirai-core-mock")
    testImplementation("net.mamoe:mirai-logging-slf4j")

    implementation(platform("io.ktor:ktor-bom:2.3.3"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("com.openhtmltopdf:openhtmltopdf-java2d:1.0.10")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.microsoft.playwright:playwright:1.60.0")
}

kotlin {
    explicitApi()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=enable"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("codexRadarPreview") {
    group = "verification"
    description = "Fetch live Codex radar data and render the chart to build/codex-radar-preview.png"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("kim.hhhhhy.x.webhook.action.codex.CodexRadarPreview")
    // 站点在部分网络下仅 IPv6 可达；可用 -Dcodex.proxy= 指定代理或置空直连
    System.getProperty("codex.proxy")?.let { systemProperty("codex.proxy", it) }
}

tasks.register<JavaExec>("polymarketLivePreview") {
    group = "verification"
    description = "Search Polymarket through the configured proxy and render the matched event summary"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("kim.hhhhhy.x.webhook.polymarket.PolymarketLivePreview")
    systemProperty("file.encoding", "UTF-8")
    args(
        System.getProperty("polymarket.query")
            ?: System.getProperty("polymarket.page")
            ?: "GPT-6",
        System.getProperty("polymarket.config") ?: "examples/webhook_config.yml",
        System.getProperty("polymarket.output") ?: "build/tmp/polymarket-live/event-summary.txt"
    )
}

tasks.register<JavaExec>("polymarketImagePreview") {
    group = "verification"
    description = "Search Polymarket and render the matched multi-market event to a local PNG"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("kim.hhhhhy.x.webhook.polymarket.PolymarketImagePreview")
    systemProperty("file.encoding", "UTF-8")
    args(
        System.getProperty("polymarket.query")
            ?: System.getProperty("polymarket.page")
            ?: "GPT-6",
        System.getProperty("polymarket.config") ?: "examples/webhook_config.yml",
        System.getProperty("polymarket.image.output") ?: "build/polymarket-event-preview.png"
    )
}

tasks.register<JavaExec>("playwrightInstallChromium") {
    group = "playwright"
    description = "Install the Playwright Chromium browser runtime"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}
